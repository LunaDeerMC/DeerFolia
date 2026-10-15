"""Minimal real TCP Minecraft client; packet IDs are read from the built version's sources."""
import collections, json, re, socket, struct, threading, time, uuid, zlib
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'DeerFolia-server/src/minecraft/java/net/minecraft/network/protocol'

def varint(n):
 n &= 0xffffffff; out=bytearray()
 while n > 127: out.append((n&127)|128); n >>= 7
 out.append(n); return bytes(out)
def readvar(data, off=0):
 n=0
 for i in range(5):
  b=data[off+i]; n |= (b&127)<<(i*7)
  if b<128: return n, off+i+1
 raise ValueError('VarInt overflow')
def string(s):
 b=s.encode(); return varint(len(b))+b
def packets(group, direction):
 s=(SOURCE/group/(group.title()+'Protocols.java')).read_text()
 s=s.split(' '+direction+'_TEMPLATE =',1)[1].split('\n    );',1)[0]
 names=re.findall(r'(?:addPacket|withBundlePacket)\(\s*[^,]+\.([A-Z_]+)',s)
 return {name:i for i,name in enumerate(names)}

class Client:
 def __init__(self,name,port=25575):
  self.name=name; self.sock=socket.create_connection(('127.0.0.1',port)); self.sock.settimeout(1)
  self.compression=False; self.threshold=256; self.state='login'; self.config_cb=packets('configuration','CLIENTBOUND'); self.running=True; self.ready=threading.Event(); self.errors=[]
  self.counts=collections.Counter(); self.teleports=[]; self.pos=(0.,0.,0.); self.messages=[]; self.lock=threading.Lock()
  self.cb=packets('game','CLIENTBOUND'); self.sb=packets('game','SERVERBOUND'); self.reverse={v:k for k,v in self.cb.items()}
  self.send(0,varint(777)+string('localhost')+struct.pack('>H',port)+varint(2))
  self.send(0,string(name)+uuid.uuid3(uuid.NAMESPACE_DNS,name).bytes)
  self.thread=threading.Thread(target=self.loop,daemon=True); self.thread.start()
 def send(self,id,payload=b''):
  body=varint(id)+payload
  if self.compression: body=varint(len(body))+zlib.compress(body) if len(body)>=self.threshold else b"\x00"+body
  with self.lock: self.sock.sendall(varint(len(body))+body)
 def exact(self,n):
  out=bytearray()
  while len(out)<n:
   try: data=self.sock.recv(n-len(out))
   except socket.timeout:
    if not self.running: raise EOFError()
    continue
   if not data: raise EOFError()
   out.extend(data)
  return bytes(out)
 def receive(self):
  length=0
  for i in range(5):
   b=self.exact(1)[0]; length|=(b&127)<<(7*i)
   if b<128: break
  data=self.exact(length)
  if self.compression:
   size,p=readvar(data); data=zlib.decompress(data[p:]) if size else data[p:]
  id,p=readvar(data); return id,data[p:]
 def activity(self): self.send(self.sb['SERVERBOUND_PUNCH'])
 def loop(self):
  try:
   while self.running:
    id,data=self.receive()
    if self.state=='login':
     if id==3: self.threshold=readvar(data)[0]; self.compression=True
     elif id==2: self.send(3); self.state='configuration'
     elif id==0: raise RuntimeError('login disconnect '+repr(data))
    elif self.state=='configuration':
     if id==self.config_cb['CLIENTBOUND_SELECT_KNOWN_PACKS']: self.send(7,varint(0))
     elif id==3: self.send(3); self.state='play'
     elif id==4: self.send(4,data)
     elif id==5: self.send(5,data)
     elif id==self.config_cb['CLIENTBOUND_CODE_OF_CONDUCT']: self.send(9)
     elif id==2: raise RuntimeError('configuration disconnect '+repr(data))
    else:
     name=self.reverse.get(id,str(id)); self.counts[name]+=1
     if name=='CLIENTBOUND_KEEP_ALIVE': self.send(self.sb['SERVERBOUND_KEEP_ALIVE'],data)
     elif name=='CLIENTBOUND_PING': self.send(self.sb['SERVERBOUND_PONG'],data)
     elif name=='CLIENTBOUND_PLAYER_POSITION':
      tid,p=readvar(data); change=struct.unpack_from('>ddd',data,p); flags=struct.unpack_from('>I',data,p+56)[0]
      self.pos=tuple(change[i]+(self.pos[i] if flags&(1<<i) else 0) for i in range(3)); self.teleports.append(self.pos)
      yrot,xrot=struct.unpack_from('>ff',data,p+48)
      self.send(self.sb['SERVERBOUND_ACCEPT_TELEPORTATION'],varint(tid)+struct.pack('>dddff',*self.pos,yrot,xrot))
      if 'SERVERBOUND_PLAYER_LOADED' in self.sb: self.send(self.sb['SERVERBOUND_PLAYER_LOADED'])
      self.ready.set()
     elif name=='CLIENTBOUND_CHUNK_BATCH_FINISHED': self.send(self.sb['SERVERBOUND_CHUNK_BATCH_RECEIVED'],struct.pack('>f',64.))
     elif name=='CLIENTBOUND_DISCONNECT': raise RuntimeError('play disconnect '+repr(data))
     elif name=='CLIENTBOUND_SYSTEM_CHAT': self.messages.append(repr(data))
  except Exception as e:
   if self.running: self.errors.append(str(e))
 def close(self):
  self.running=False; self.sock.close(); self.thread.join(3)
