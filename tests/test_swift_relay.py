"""Real iOS relay and Network.framework, substituting the Bluetooth streams with TCP on CI.
No BLE hardware, OS VPN, pairing, radio speed or background execution is simulated.
"""
import concurrent.futures
import contextlib
import hashlib
import os
import socket
import struct
import subprocess
import sys
import threading
import unittest

BINARY = sys.argv.pop() if len(sys.argv) > 1 else '/tmp/bt6-relay-harness'

def read(sock, size):
    data = bytearray()
    while len(data) < size:
        part = sock.recv(size-len(data))
        if not part:
            raise EOFError((len(data), size))
        data.extend(part)
    return bytes(data)

class ResponseStream:
    def __init__(self,sock): self.sock=sock;self.pending=bytearray();self.ended=False
    def sendall(self,data): self.sock.sendall(data)
    def settimeout(self,value): self.sock.settimeout(value)
    def shutdown(self,how): self.sock.shutdown(how)
    def recv(self,size):
        if self.ended: return b''
        if not self.pending:
            kind,length=struct.unpack('!BH',read(self.sock,3))
            if kind==0xD2 and length==0: self.ended=True;return b''
            if kind!=0xD1 or not 1<=length<=16384: raise ValueError('invalid frame')
            self.pending.extend(read(self.sock,length))
        data=bytes(self.pending[:size]);del self.pending[:size];return data
    def __enter__(self): return self
    def __exit__(self,*args): self.sock.close()

@contextlib.contextmanager
def channel():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0)); listener.listen(); listener.settimeout(10)
        process = subprocess.Popen([BINARY, str(listener.getsockname()[1])], env={**os.environ, 'BT6_TRACE':'1'})
        try:
            sock, _ = listener.accept()
            with sock:
                sock.settimeout(10)
                yield ResponseStream(sock)
        finally:
            process.terminate()
            process.wait(timeout=5)

def addr(port):
    return b'\x01\x7f\x00\x00\x01' + struct.pack('!H', port)

def negotiate(sock, command, port):
    # Deliberately split greeting and command in individual bytes.
    for b in b'\x05\x01\x00':
        sock.sendall(bytes([b]))
    assert read(sock, 2) == b'\x05\x00'
    for b in b'\x05' + bytes([command, 0]) + addr(port):
        sock.sendall(bytes([b]))
    reply = read(sock, 10)
    assert reply[:2] == b'\x05\x00', reply

class RelayTests(unittest.TestCase):
    def test_tcp_large_full_duplex(self):
        with socket.socket() as echo:
            echo.bind(('127.0.0.1',0)); echo.listen()
            payload=os.urandom(2*1024*1024)
            def server():
                c,_=echo.accept()
                with c:
                    c.settimeout(10)
                    data=read(c,len(payload))
                    # Network-to-BLE data with an EOF immediately after it.
                    c.sendall(data)
            with concurrent.futures.ThreadPoolExecutor() as pool:
                task=pool.submit(server)
                with channel() as c:
                    negotiate(c,1,echo.getsockname()[1])
                    c.sendall(payload)
                    actual=read(c,len(payload))
                    self.assertEqual(hashlib.sha256(actual).digest(),hashlib.sha256(payload).digest())
                    self.assertEqual(c.recv(1),b'')
                    c.sock.settimeout(0.2)
                    with self.assertRaises(TimeoutError): c.sock.recv(1)  # Physical channel awaits recipient ACK.
                task.result(timeout=10)

    def test_udp_frames(self):
        with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as udp:
            udp.bind(('127.0.0.1',0)); udp.settimeout(10)
            def server():
                for _ in range(8):
                    data,peer=udp.recvfrom(65535);udp.sendto(data,peer)
            thread=threading.Thread(target=server,daemon=True);thread.start()
            with channel() as c:
                negotiate(c,5,0)
                target=addr(udp.getsockname()[1])
                for size in [1,17,512,1024,1200,1232,4096,0]:
                    print("UDP test size",size,flush=True)
                    data=os.urandom(size)
                    packet=struct.pack('!HB',size,3+len(target))+target+data
                    c.sendall(packet)
                    header=read(c,3)
                    length,offset=struct.unpack('!HB',header)
                    self.assertEqual(offset,10);self.assertEqual(length,size)
                    self.assertEqual(read(c,7),target)
                    self.assertEqual(read(c,length),data)
            thread.join(timeout=5);self.assertFalse(thread.is_alive())

    def test_peer_eof_releases_channel(self):
        with socket.socket() as target, socket.socket() as listener:
            target.bind(('127.0.0.1',0)); target.listen(); target.settimeout(10)
            listener.bind(('127.0.0.1',0)); listener.listen(); listener.settimeout(10)
            process=subprocess.Popen([BINARY,str(listener.getsockname()[1])])
            try:
                raw_peer,_=listener.accept()
                with ResponseStream(raw_peer) as peer:
                    peer.settimeout(10)
                    negotiate(peer,1,target.getsockname()[1])
                    remote,_=target.accept()
                    with remote:
                        # A Bluetooth stream cannot preserve a TCP half-close;
                        # the same input EOF in this harness must free its slot.
                        peer.shutdown(socket.SHUT_WR)
                        self.assertEqual(process.wait(timeout=3),0)
            finally:
                if process.poll() is None: process.terminate();process.wait(timeout=5)

    def test_denied_auth(self):
        with channel() as c:
            c.sendall(b'\x05\x01\x02')
            self.assertEqual(read(c,2),b'\x05\xff')
            self.assertEqual(c.recv(1),b'')

    def test_unsupported_command(self):
        with channel() as c:
            c.sendall(b'\x05\x01\x00');read(c,2)
            c.sendall(b'\x05\x02\x00'+addr(80))
            self.assertEqual(read(c,10)[1],7)

    def test_connect_failure(self):
        with socket.socket() as reserved:
            reserved.bind(('127.0.0.1',0))
            with channel() as c:
                c.sendall(b'\x05\x01\x00');read(c,2)
                c.sendall(b'\x05\x01\x00'+addr(reserved.getsockname()[1]))
                self.assertNotEqual(read(c,10)[1],0)

if __name__=='__main__':
    unittest.main(verbosity=2)
