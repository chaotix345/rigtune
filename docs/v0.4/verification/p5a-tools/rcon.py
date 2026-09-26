import socket, struct, sys
def pkt(i, t, body):
    b = body.encode('utf-8') + b'\x00\x00'
    return struct.pack('<iii', len(b) + 8, i, t) + b
def recv(s):
    n = struct.unpack('<i', s.recv(4))[0]
    data = b''
    while len(data) < n:
        data += s.recv(n - len(data))
    i, t = struct.unpack('<ii', data[:8])
    return i, t, data[8:-2].decode('utf-8', 'replace')
s = socket.create_connection(('127.0.0.1', 25578), timeout=10)
s.sendall(pkt(1, 3, 'p5a-local-only')); i, t, _ = recv(s)
if i == -1: sys.exit('rcon auth failed')
s.sendall(pkt(2, 2, ' '.join(sys.argv[1:]))); print(recv(s)[2])
