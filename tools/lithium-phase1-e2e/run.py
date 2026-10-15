"""Real server A/B integration runs. Only new phase-one flags differ between processes.

python3 tools/lithium-phase1-e2e/compile.py
python3 tools/lithium-phase1-e2e/run.py --modes off,on,on,off --restart

Each run uses a fresh isolated flat world, two real TCP clients in separate Folia
regions, twenty warmup rounds and twenty measured rounds per scenario. No TPS claims.
"""
import argparse, hashlib, json, os, re, shutil, socket, statistics, subprocess, sys, time, traceback
from pathlib import Path
from zipfile import ZipFile

sys.dont_write_bytecode = True
from support import ROOT, HERE, OUT, java_tool, jvm_properties
from client import Client

FLAGS=['block-effects','heightmaps','game-events','brain-tasks','brain-memory','block-search','block-entity-sleeping']
JAVA=java_tool('java')
PORT=25577

def props():
    return jvm_properties()

def prepare(run,jar,mode,baseline=False):
    run.mkdir(parents=True,exist_ok=True)
    with ZipFile(jar) as z: digest,url,filename=z.read('META-INF/download-context').decode().strip().split('\t')
    vanilla=ROOT/'DeerFolia-server/.gradle/caches/paperweight/taskCache/downloadServerJar.jar'
    assert hashlib.sha256(vanilla.read_bytes()).hexdigest()==digest
    (run/'cache').mkdir(exist_ok=True);shutil.copyfile(vanilla,run/'cache'/filename)
    (run/'plugins').mkdir(exist_ok=True);shutil.copyfile(OUT/'LithiumPhase1E2E.jar',run/'plugins/LithiumPhase1E2E.jar')
    (run/'eula.txt').write_text('eula=true\n')
    (run/'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={PORT}
online-mode=false
enforce-secure-profile=false
view-distance=4
simulation-distance=4
level-type=minecraft:flat
level-seed=719225
spawn-protection=0
gamemode=creative
max-players=4
network-compression-threshold=256
white-list=false
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:dirt","height":2}},{{"block":"minecraft:grass_block","height":1}}],"biome":"minecraft:plains"}}
''')
    (run/'config').mkdir(exist_ok=True)
    (run/'config/paper-global.yml').write_text('_version: 31\nthreaded-regions:\n  threads: 2\n')
    enabled='true' if mode=='on' else 'false'
    (run/'config/deer-folia.yml').write_text('lithium-optimize:\n  enabled: true\n'+('' if baseline else ''.join(f'  {f}: {enabled}\n' for f in FLAGS))+'afk-network-optimization:\n  afk-threshold-ticks: 12000\n')

def execute(jar,mode,tag,restart=False,benchmark=True,baseline=False,warmup_rounds=20,sample_rounds=20):
    run=OUT/'runs'/tag
    prepare(run,jar,mode,baseline)
    logfile=OUT/'results'/(tag+('-restart' if restart else '')+'.log')
    logfile.parent.mkdir(exist_ok=True)
    stream=logfile.open('w')
    command=[str(JAVA),'-Xms1G','-Xmx3G','--enable-native-access=ALL-UNNAMED',*props(),'-Dminecraft.api.discovery.host=http://127.0.0.1:25576/discovery',f'-Dphase1.optimized={str(mode=="on").lower()}',f'-Dphase1.baseline={str(baseline).lower()}',f'-Dphase1.warmupRounds={warmup_rounds}',f'-Dphase1.sampleRounds={sample_rounds}','-jar',str(jar),'--nogui']
    process=subprocess.Popen(command,cwd=run,stdin=subprocess.PIPE,stdout=stream,stderr=subprocess.STDOUT,text=True)
    clients=[]
    result={'tag':tag,'mode':mode,'restart':restart,'baseline_artifact':baseline,'warmup_rounds':warmup_rounds,'sample_rounds':sample_rounds,'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'checks':[],'status':'failed'}
    def content(): stream.flush();return logfile.read_text()
    def console(s): process.stdin.write(s+'\n');process.stdin.flush()
    def wait(marker,timeout=180):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            data=content()
            if 'PHASE1_FAIL' in data: raise AssertionError(data[data.index('PHASE1_FAIL'):][-5000:])
            if marker in data:return
            if process.poll() is not None: raise RuntimeError('server exited: '+data[-4000:])
            for client in clients:
                if client.ready.is_set() and not client.errors: client.activity()
            time.sleep(.25)
        raise TimeoutError(marker+'\n'+content()[-4000:])
    try:
        wait('Done (');wait('PHASE1_PASS plugin-enabled')
        for name in ['TestIdle','TestActive']:
            client=Client(name,PORT);clients.append(client)
            if not client.ready.wait(60):raise RuntimeError('TCP login failed '+name+' '+str(client.errors))
            result['checks'].append('tcp-login-'+name)
        if restart:
            for client in clients:console('phase1 restart '+client.name)
            for client in clients:wait('PHASE1_PASS restart-complete-'+client.name)
        else:
            for client in clients:console('phase1 setup '+client.name)
            for client in clients:wait('PHASE1_PASS setup-complete-'+client.name)
            if not baseline:
                for client in clients:console('phase1 functional '+client.name)
                for client in clients:
                    wait('PHASE1_PASS functional-sync-complete-'+client.name)
                    wait('PHASE1_PASS wakeup-complete-'+client.name)
                    wait('PHASE1_PASS paths-complete-'+client.name)
                    wait('PHASE1_PASS poi-complete-'+client.name)
            if benchmark:
                # Sequential region benchmarks reduce inter-region timing interference.
                # Both regions remain live with their TCP clients throughout every sample.
                for client in clients:
                    console('phase1 bench '+client.name)
                    wait('PHASE1_PASS benchmark-complete-'+client.name,240)
        for client in clients:
            if client.errors:raise AssertionError('TCP errors '+client.name+': '+str(client.errors))
        result['status']='passed'
    except Exception:
        result['error']=traceback.format_exc();print(result['error'],flush=True)
    finally:
        if process.poll() is None:
            console('stop')
            try:process.wait(timeout=60)
            except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=15)
        for client in clients:client.close()
        stream.close()
        data=logfile.read_text()
        result['exit_code']=process.returncode
        result['plugin_checks']=re.findall(r'PHASE1_PASS ([^\n]+)',data)
        result['samples']=[json.loads(s) for s in re.findall(r'PHASE1_SAMPLE (\{[^\n]+\})',data)]
        result['errors_in_log']=[s for s in data.splitlines() if re.search(r'\b(ERROR|SEVERE)\b|Exception|PHASE1_FAIL',s)]
        result['packets']={c.name:dict(c.counts) for c in clients}
        if result['exit_code'] or result['errors_in_log']:result['status']='failed'
        logfile.with_suffix('.json').write_text(json.dumps(result,indent=2))
        print(json.dumps({k:v for k,v in result.items() if k not in ['samples','packets','plugin_checks']},indent=2),flush=True)
    return result

def summarize(results):
    groups={}
    for result in results:
        for sample in result.get('samples',[]):groups.setdefault((sample['scenario'],result['mode']),[]).append(sample)
    rows=[]
    for scenario in sorted({s for s,m in groups}):
        row={'scenario':scenario}
        for mode in ['off','on']:
            samples=groups.get((scenario,mode),[])
            if not samples:continue
            row[mode]={'samples':len(samples),'median_ns_per_operation':statistics.median(s['nanos']/s['operations'] for s in samples),'median_cpu_ns_per_operation':statistics.median(s['cpu_nanos']/s['operations'] for s in samples),'median_bytes_per_operation':statistics.median(s['allocated_bytes']/s['operations'] for s in samples),'work_per_operation':sorted(set(s['work']/s['operations'] for s in samples))}
        if all(m in row for m in ['off','on']):
            row['time_change_percent']=(row['on']['median_ns_per_operation']/row['off']['median_ns_per_operation']-1)*100
            row['cpu_change_percent']=(row['on']['median_cpu_ns_per_operation']/row['off']['median_cpu_ns_per_operation']-1)*100
            row['equivalent_work']=row['off']['work_per_operation']==row['on']['work_per_operation']
        rows.append(row)
    summary={'status':'passed' if all(r['status']=='passed' for r in results) else 'failed','runs':[{k:r[k] for k in ['tag','mode','restart','status','jar_sha256']} for r in results],'methodology':'Same fixed safety implementation in both arms; only seven phase-one flags differ. Real two-region server with TCP clients. Warmup and sample round counts are stored in each raw run result (default twenty each). Region-thread elapsed nanos and ThreadMXBean allocated bytes. Full BlockEntity ticker wrapper, not only static tick method. Synthetic subsystem timings are not whole-server throughput or TPS claims.','comparison':rows}
    if any(not row.get('equivalent_work',True) for row in rows):summary['status']='failed'
    (OUT/'results/summary.json').write_text(json.dumps(summary,indent=2))
    print(json.dumps(summary,indent=2),flush=True)
    return summary

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--jar',type=Path,default=ROOT/'DeerFolia-server/build/libs/DeerFolia-paperclip-26.3-R0.1-SNAPSHOT.jar')
    parser.add_argument('--modes',default='off,on,on,off')
    parser.add_argument('--tag',default=time.strftime('%Y%m%d-%H%M%S'))
    parser.add_argument('--restart',action='store_true')
    parser.add_argument('--warmup-rounds',type=int,default=20)
    parser.add_argument('--sample-rounds',type=int,default=20)
    parser.add_argument('--baseline',action='store_true',help='Original artifact benchmark only, separate from phase-one A/B')
    parser.add_argument('--no-benchmark',action='store_true')
    args=parser.parse_args()
    OUT.mkdir(parents=True,exist_ok=True)
    if args.warmup_rounds<0 or args.sample_rounds<1:parser.error("warmup-rounds must be nonnegative and sample-rounds positive")
    modes=args.modes.split(',')
    if any(mode not in ['off','on'] for mode in modes):parser.error("modes must be a comma-separated list of off/on")
    if args.baseline and modes!=['off']:parser.error("baseline reference requires --modes off")
    if args.baseline and args.restart:parser.error("baseline reference does not support --restart; restart checks require the final artifact")
    relay=None;relay_log=None
    try:
        try:
            with socket.create_connection(('127.0.0.1',25576),timeout=1):pass
        except OSError:
            relay_log=(OUT/'relay.log').open('w')
            relay=subprocess.Popen([sys.executable,str(HERE/'services_proxy.py')],stdout=relay_log,stderr=subprocess.STDOUT)
            time.sleep(.3)
        results=[]
        for index,mode in enumerate(modes):
            tag=f'{args.tag}-{index}-{mode}'
            result=execute(args.jar.resolve(),mode,tag,benchmark=not args.no_benchmark,baseline=args.baseline,warmup_rounds=args.warmup_rounds,sample_rounds=args.sample_rounds);results.append(result)
            if result['status']!='passed':break
            if args.restart:
                result=execute(args.jar.resolve(),mode,tag,restart=True,warmup_rounds=args.warmup_rounds,sample_rounds=args.sample_rounds);results.append(result)
                if result['status']!='passed':break
        summary=summarize(results)
        if args.baseline:summary['methodology']='Original a81b44b artifact reference run, not the fixed-safety phase-one feature A/B. Functional new-API assertions skipped. Synthetic scenarios identical; assess full Brain.tick and complete BlockEntity wrappers for total new common overhead.'
        (OUT/'results'/(args.tag+'-summary.json')).write_text(json.dumps(summary,indent=2))
        sys.exit(0 if summary['status']=='passed' else 1)
    finally:
        if relay:relay.terminate();relay.wait(timeout=10)
        if relay_log:relay_log.close()
