"""Loopback relay for Mojang services in environments that require an HTTP proxy.
Only discovery URLs are rewritten; public keys and other responses come from official HTTPS endpoints.
"""
import http.server,json,subprocess,urllib.parse
ALLOWED={'discovery.minecraftservices.com','api.minecraftservices.com','sessionserver.mojang.com','api.mojang.com','textures.minecraft.net'}
class Relay(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  if self.path=='/discovery': url='https://discovery.minecraftservices.com/minecraft/client'
  else:
   host,sep,path=self.path.lstrip('/').partition('/')
   if host not in ALLOWED: self.send_error(403); return
   url='https://'+host+'/'+path
  result=subprocess.run(['curl','--fail-with-body','--silent','--show-error','--max-time','20',url],capture_output=True)
  if result.returncode:
   self.send_error(502,result.stderr.decode()); return
  body=result.stdout
  if self.path=='/discovery':
   data=json.loads(body)
   def rewrite(x):
    if isinstance(x,dict): return {k:rewrite(v) for k,v in x.items()}
    if isinstance(x,list): return [rewrite(v) for v in x]
    if isinstance(x,str) and x.startswith('https://') and urllib.parse.urlparse(x).hostname in ALLOWED:
     return 'http://127.0.0.1:25576/'+x.removeprefix('https://')
    return x
   body=json.dumps(rewrite(data)).encode()
  self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
http.server.ThreadingHTTPServer(('127.0.0.1',25576),Relay).serve_forever()
