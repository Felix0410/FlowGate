#!/usr/bin/env python3
"""仅自有隔离实例的离线维护工具：只读检查 → 全Control退出 → ADMIN会话 → epoch CAS/审计。"""
import argparse,hashlib,json,os,re,subprocess,sys,time,uuid
from pathlib import Path
import v11_local_core as c
import v11_local_runtime as r

def controls(home):
    raw=subprocess.run(['ps','-axo','pid=,command='],text=True,capture_output=True,check=True).stdout
    # 1. 隔离单机清单是本工具的明确边界；未知Control/IDE一律阻断，绝不停止它们。
    rows=[]
    for line in raw.splitlines():
        if ('-jar ' in line and 'flowgate-control.jar' in line) or re.search(r'\bcom\.flowgate\.control\.\w*Application\b',line):
            pid,cmd=line.strip().split(None,1);rows.append({'pid':int(pid),'owned':str(home/'artifacts/flowgate-control.jar') in cmd})
    return rows

def inspect(home):
    state,env=r.load(home);r.containers(state,env);r.volume_identities(state)
    sql=lambda q,ro=True:c.mysql_query(env,q,state['project'],state['owner'],read_only=ro)
    mode,epoch,high=sql('SELECT mode,epoch,cutover_max_outbox_id FROM fg_legacy_mq_cutover WHERE id=1;').split('\t')
    leases=int(sql("SELECT COUNT(*) FROM fg_outbox_event WHERE status='LEASED' AND lease_until>=UTC_TIMESTAMP(3);"))
    processes=controls(home)
    return state,env,sql,{'mode':mode,'epoch':int(epoch),'highWatermark':int(high),'activeLeases':leases,'controls':processes,'oldInFlight':0 if not processes and not leases else 'UNKNOWN'}

def health(sql,high):
    # 2. 原始Outbox八字段与Ledger、去重指纹逐笔核验；异常停止，不自动修账。
    orphan=int(sql("SELECT COUNT(*) FROM fg_quota_ledger l WHERE NOT EXISTS(SELECT 1 FROM fg_outbox_event o WHERE o.event_type='USAGE_GRANTED' AND JSON_UNQUOTE(JSON_EXTRACT(o.payload_json,'$.chargeId'))=l.charge_id);"))
    orphan_consumed=int(sql('SELECT COUNT(*) FROM fg_consumed_event c WHERE NOT EXISTS(SELECT 1 FROM fg_outbox_event o WHERE o.event_id=c.event_id);'))
    if orphan or orphan_consumed:raise c.LocalFailure('INTEGRITY_GAP: orphan Ledger/Consumed')
    cursor=0;count=0
    while True:
        raw=sql("SELECT JSON_OBJECT('id',o.id,'eventId',o.event_id,'eventType',o.event_type,'payload',o.payload_json,'app',l.application_id,'api',l.api_id,'units',l.units,'period',l.period_yyyymm,'charge',l.charge_id,'duplicate',(SELECT COUNT(*) FROM fg_outbox_event d WHERE JSON_UNQUOTE(JSON_EXTRACT(d.payload_json,'$.chargeId'))=l.charge_id),'consumedCharge',c.charge_id,'consumedSha',c.payload_sha256) FROM fg_outbox_event o LEFT JOIN fg_quota_ledger l ON l.charge_id=JSON_UNQUOTE(JSON_EXTRACT(o.payload_json,'$.chargeId')) LEFT JOIN fg_consumed_event c ON c.event_id=o.event_id WHERE o.id>"+str(cursor)+' AND o.id<='+str(high)+' ORDER BY o.id LIMIT 100;')
        if not raw:break
        for line in raw.splitlines():
            row=json.loads(line);p=row['payload'];cursor=int(row['id']);count+=1
            fields=['version','eventId','chargeId','applicationId','apiId','periodUtc','units','eventTime']
            if set(p)!=set(fields) or p['version']!=1 or row['eventType']!='USAGE_GRANTED' or row['eventId']!=p['eventId'] or row['duplicate']!=1:raise c.LocalFailure('INTEGRITY_GAP: source schema')
            for k in ('version','applicationId','apiId','units'):
                if type(p[k]) is not int:raise c.LocalFailure('INTEGRITY_GAP: numeric type')
            if not 1<=p['units']<=100 or p['applicationId']<=0 or p['apiId']<=0:raise c.LocalFailure('INTEGRITY_GAP: units/identity')
            for k in ('eventId','chargeId'):
                if str(uuid.UUID(p[k]))!=p[k]:raise c.LocalFailure('INTEGRITY_GAP: UUID')
            # Java Instant格式只允许UTC；标准RFC3339解析同时验证日期和月份。
            from datetime import datetime
            stamp=p['eventTime'];parsed=datetime.fromisoformat(stamp.replace('Z','+00:00'))
            if not stamp.endswith('Z') or parsed.strftime('%Y%m')!=p['periodUtc']:raise c.LocalFailure('INTEGRITY_GAP: UTC')
            if [row[k] for k in ('charge','app','api','units','period')]!=[p[k] for k in ('chargeId','applicationId','apiId','units','periodUtc')]:raise c.LocalFailure('INTEGRITY_GAP: Ledger conflict')
            # canonical Instant按Java的0/3/6/9位表示；其余字段固定record顺序。
            if '.' in stamp:
                prefix,fraction=stamp[:-1].split('.');fraction=fraction.rstrip('0');stamp=prefix+('.'+fraction.ljust(((len(fraction)+2)//3)*3,'0') if fraction else '')+'Z'
            canonical={k:(stamp if k=='eventTime' else p[k]) for k in fields};sha=hashlib.sha256(json.dumps(canonical,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
            if row['consumedCharge'] is not None and (row['consumedCharge']!=p['chargeId'] or row['consumedSha']!=sha):raise c.LocalFailure('INTEGRITY_GAP: consumed fingerprint')
    # 日表以原事件UTC日和Consumed事实比对，缺失/多余行都阻断。
    gap=sql("SELECT COUNT(*) FROM (SELECT d,app,api,SUM(u) u,SUM(n) n FROM (SELECT LEFT(JSON_UNQUOTE(JSON_EXTRACT(o.payload_json,'$.eventTime')),10) d,l.application_id app,l.api_id api,l.units u,1 n FROM fg_consumed_event c JOIN fg_outbox_event o ON o.event_id=c.event_id JOIN fg_quota_ledger l ON l.charge_id=c.charge_id UNION ALL SELECT CONVERT(CAST(day_utc AS CHAR) USING utf8mb4) COLLATE utf8mb4_bin,application_id,api_id,-authorized_units,-grant_count FROM fg_usage_daily) a GROUP BY d,app,api HAVING u<>0 OR n<>0) gaps;")
    if int(gap):raise c.LocalFailure('INTEGRITY_GAP: daily mismatch')
    return count

def actor(env,token_file):
    if token_file.is_symlink() or token_file.stat().st_mode&0o077:raise c.LocalFailure('ADMIN token文件必须0600且非符号链接')
    token=token_file.read_text().strip();digest=hashlib.sha256(token.encode()).hexdigest()
    owner=env['FG_V11_DEMO_OWNER'];cid=c.docker_target(int(env['FG_REDIS_PORT']),'redis','flowgate-v11-demo-'+owner[:12],owner)
    data=c.command(['docker','exec','-i','-e','REDISCLI_AUTH',cid,'redis-cli','--raw','-x','GET'],env=dict(os.environ,REDISCLI_AUTH=env['FG_REDIS_PASSWORD']),data='fg:auth:admin:'+digest)
    session=json.loads(data)
    if session.get('principalType')!='ADMIN' or session.get('role')!='ADMIN':raise c.LocalFailure('仅有效ADMIN会话允许迁移')
    return int(session['principalId'])

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--home',type=Path,required=True);p.add_argument('--target',choices=['FROZEN','RABBIT']);p.add_argument('--expect-mode',choices=['KAFKA','FROZEN','RABBIT']);p.add_argument('--expect-epoch',type=int);p.add_argument('--admin-token-file',type=Path);args=p.parse_args()
    home=args.home.resolve();state,env,sql,report=inspect(home)
    if not args.target:print(json.dumps(report));return 0
    if report['controls'] or report['activeLeases']:raise c.LocalFailure('仍有Control或在途租约；先核对全部旧实例退出，等待租约过期')
    if not args.admin_token_file or args.expect_mode is None or args.expect_epoch is None:raise c.LocalFailure('须提供ADMIN会话、预期mode和epoch')
    who=actor(env,args.admin_token_file);old=report['mode'];target=args.target
    allowed={('KAFKA','FROZEN'),('FROZEN','RABBIT'),('RABBIT','FROZEN')}
    if (old,target) not in allowed or old!=args.expect_mode or report['epoch']!=args.expect_epoch:raise c.LocalFailure('模式或代次冲突；重新读取，禁止force')
    high=int(sql('SELECT COALESCE(MAX(id),0) FROM fg_outbox_event;'));checked=health(sql,high)
    # 3. DB CAS与审计同事务；全进程退出后不会有旧回调越过栅栏。仅控制表与审计写入。
    q="START TRANSACTION; UPDATE fg_legacy_mq_cutover SET mode='"+target+"',epoch=epoch+1,cutover_max_outbox_id="+str(high)+",changed_at=UTC_TIMESTAMP(3),changed_by="+str(who)+" WHERE id=1 AND mode='"+old+"' AND epoch="+str(args.expect_epoch)+"; SET @changed=ROW_COUNT(); INSERT INTO fg_audit_log(actor_id,action,target_type,target_id,summary) SELECT "+str(who)+",'LEGACY_MQ_CUTOVER','legacy_transport','1','"+old+'->'+target+'; epoch '+str(args.expect_epoch+1)+'; highWatermark '+str(high)+"' WHERE @changed=1; SELECT @changed; COMMIT;"
    if sql(q,False)!='1':raise c.LocalFailure('epoch CAS失败，无审计变更')
    print(json.dumps({'previous':report,'target':target,'epoch':args.expect_epoch+1,'highWatermark':high,'validatedEvents':checked,'nextStep':'启动同一兼容版本的受控Control集合；核验冷却后由ADMIN逐条原事件补投'}));return 0
if __name__=='__main__':
    try:raise SystemExit(main())
    except (c.LocalFailure,ValueError,KeyError,OSError):print('迁移已阻断：实例/ADMIN/模式/租约/源数据完整性未通过；保持原模式，检查维护清单',file=sys.stderr);raise SystemExit(2)
