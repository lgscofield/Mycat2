#!/usr/bin/env bash
# G8 CI 红项分类器: run 完成后一键执行(取 CI 日志按类聚合+抽样定性)
# 用法: bash classify-g8-reds.sh <run-id>   (默认 42849914398)
RID="${1:-42849914398}"
A="D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit"
echo "=== CI run $RID 红项分类 ==="
# 按 job 取失败步骤日志
timeout 25 curl -s "https://api.github.com/repos/lgscofield/Mycat2/actions/runs/$RID/jobs" -o "$A/jobs-final.json"
python -c "
import json
d=json.load(open(r'$A/jobs-final.json'))
for j in d['jobs']:
    print('job:', j['name'], j['status'], j.get('conclusion'))
    for s in j['steps']:
        print('  step:', s['name'], s['status'], s.get('conclusion'))
"
# 红项测试类聚合(从 CI 日志 URL 拉取——公开仓库 API 取 job 日志)
python - <<'EOF'
import json, urllib.request
d=json.load(open(r'D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit/jobs-final.json'))
for j in d['jobs']:
    if j.get('conclusion') in ('failure','cancelled'): 
        u=j['url']+'/logs'
        try:
            req=urllib.request.Request(u, headers={'Accept':'application/vnd.github.v3+json'})
            logs=urllib.request.urlopen(req, timeout=60).read().decode('utf-8','replace')
            import re
            fails=re.findall(r'\[ERROR\]\s+([A-Za-z0-9_.]+Test)\.', logs)
            from collections import Counter
            c=Counter(fails)
            print('---', j['name'], '红类 top:')
            for k,v in c.most_common(15): print('   ', v, k)
            open(r'D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit/logs/ci-full-suite.log','w',encoding='utf-8',errors='replace').write(logs)
            print('全量日志已存 logs/ci-full-suite.log (', len(logs), 'chars )')
        except Exception as e:
            print('拉取失败:', e)
EOF
