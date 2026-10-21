#!/usr/bin/env python3
"""Authorized isolated API acceptance. Only loopback port 18011 and private fixture credentials.
No token, credential or complete authentication reply is printed or saved in evidence.
"""
import argparse,json,urllib.request,urllib.error,urllib.parse,subprocess,os
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--execute',action='store_true',required=True)
p.add_argument('--credentials',type=Path,required=True)
a=p.parse_args()
assert str(a.credentials).startswith('/private/tmp/iw-mtype-')
creds=json.loads(a.credentials.read_text());tokens={};users={};base='http://127.0.0.1:18011'
D='/bookkeeping-service/bookkeeping/dictionaries';M='/bookkeeping-service/bookkeeping/membership';F='/auth-service/family/group'
count=0

def req(method,path,role='a',body=None,ok=True):
    global count
    headers={'Content-Type':'application/json'}
    if role in tokens:headers['iwtoken']=tokens[role]
    request=urllib.request.Request(base+path,method=method,headers=headers,data=None if body is None else json.dumps(body,ensure_ascii=False).encode())
    try:
        with urllib.request.urlopen(request,timeout=20) as r: result=json.load(r)
    except urllib.error.HTTPError as e:
        try: result=json.load(e)
        except Exception: result={'code':e.code}
    passed=result.get('code')==200
    assert passed==ok, f'{method} {path.split("?")[0]} expected success={ok}; code={result.get("code")}; message={result.get("message",result.get("msg",""))}'
    count+=1
    return result.get('data')

def items(role='a',kind='MEMBERSHIP_TYPE',scope='PERSONAL'):return req('GET',D+'/items?type='+kind+'&scope='+scope,role)
def add(name,role='a',kind='MEMBERSHIP_TYPE'):return req('POST',D+'/items',role,{'type':kind,'scope':'PERSONAL','name':name})
def update_sub(id,**kw):
    body={'id':id,'membershipName':'AT-MTYPE-编辑其它字段','amount':12.5,'billingCycle':1,'startDate':'2026-10-21','autoRenew':True,**kw}
    return req('PUT',M+'/update',body=body)
def sub(source,role='a',ok=True):
    return req('POST',M+'/add',role,{'membershipTypeId':source,'membershipName':'AT-MTYPE-测试订阅','amount':12.5,'billingCycle':1,'startDate':'2026-10-21','autoRenew':True},ok)
def detail(id,role='a',ok=True):return req('GET',M+'/detail?id='+str(id),role,ok=ok)
def sql(statement):
    result=subprocess.run(['/usr/local/mysql/bin/mysql','--no-defaults','--socket=/private/tmp/iw-mtype-20261021-r1/mysql.sock','-uroot','--batch','--raw','--default-character-set=utf8mb4','mtype_api_r1'],input=statement,text=True,capture_output=True)
    assert result.returncode==0,result.stderr[:500]
    return result.stdout
try:
    server=sql('SELECT @@datadir,@@port,DATABASE();').splitlines()[-1].split('\t')
    assert server[0].startswith('/private/tmp/iw-mtype-') and server[1:]==['13371','mtype_api_r1'], 'Requires this run disposable MySQL fixture'
    for role in 'abc':
        response=req('POST','/auth-service/login/password',role,creds[role]);tokens[role]=response['tokenValue'];users[role]=response['id']
    print('PASS normal password authentication for three generated ordinary accounts')
    assert items()['canManage'] is True
    group=req('POST',F+'/add','b',{'groupName':'AT-MTYPE-隔离家庭','maxMember':10})
    invite=req('POST',F+'/generateInvite','b',{'groupId':group,'validHours':1})
    req('POST',F+'/join','c',{'inviteCode':invite['inviteCode']})
    actual=req('GET',F+'/myGroup','c');assert actual['currentUserRole']==3
    per_role={role:add('AT-MTYPE-'+role,role) for role in 'abc'}
    for role in 'abc':assert items(role)['canManage'] is True
    print('PASS no-family user, actual family owner and actual member can independently manage personal 4004')
    for method,path,body in [('GET',D+'/items?type=MEMBERSHIP_TYPE&scope=FAMILY',None),('POST',D+'/items',{'type':'MEMBERSHIP_TYPE','scope':'FAMILY','name':'禁止'}),('PUT',D+'/order',{'type':'MEMBERSHIP_TYPE','scope':'FAMILY','ids':[],'scopeVersion':'x'})]:
        req(method,path,'b',body,False)
    req('POST',D+'/copy-personal','b',{'sourceIds':[per_role['b']['id']]},False)
    foreign=per_role['b']['id']
    req('PUT',D+f'/items/{foreign}/name',body={'name':'越权','revision':0},ok=False)
    req('PUT',D+f'/items/{foreign}/status',body={'enabled':False,'revision':0},ok=False)
    req('DELETE',D+f'/items/{foreign}?revision=0',ok=False)
    print('PASS family list/create/order/copy and foreign IDs rejected by backend')
    valid=add('  AT-MTYPE-原名  ');assert valid['name']=='AT-MTYPE-原名';source=valid['id']
    emoji=add('🍚'*32)
    req('POST',D+'/items',body={'type':'MEMBERSHIP_TYPE','scope':'PERSONAL','name':'🍚'*33},ok=False)
    req('POST',D+'/items',body={'type':'MEMBERSHIP_TYPE','scope':'PERSONAL','name':'　 '},ok=False)
    disabled=add('AT-MTYPE-停用');disabled=req('PUT',D+f"/items/{disabled['id']}/status",body={'enabled':False,'revision':disabled['revision']})
    req('POST',D+'/items',body={'type':'MEMBERSHIP_TYPE','scope':'PERSONAL','name':'AT-MTYPE-停用'},ok=False)
    before=items();ids=[x['id'] for x in before['items']][::-1]
    req('PUT',D+'/order',body={'type':'MEMBERSHIP_TYPE','scope':'PERSONAL','ids':ids,'scopeVersion':before['scopeVersion']})
    assert [x['id'] for x in items()['items']]==ids
    req('PUT',D+'/order',body={'type':'MEMBERSHIP_TYPE','scope':'PERSONAL','ids':ids,'scopeVersion':before['scopeVersion']},ok=False)
    # Order increments revisions. Always use current management revision after reordering.
    valid=next(x for x in items()['items'] if x['id']==source)
    subscription=sub(source);assert detail(subscription)['membershipTypeName']=='AT-MTYPE-原名'
    for bad_id in [foreign,disabled['id'],99999999]:sub(bad_id,ok=False)
    category=add('AT-MTYPE-分类',kind='CATEGORY');sub(category['id'],ok=False)
    req('POST',M+'/add',body={'membershipType':1,'membershipName':'旧客户端','billingCycle':1,'startDate':'2026-10-21'},ok=False)
    renamed=req('PUT',D+f'/items/{source}/name',body={'name':'AT-MTYPE-新名','revision':valid['revision']})['item']
    req('PUT',D+f'/items/{source}/name',body={'name':'过期版本','revision':valid['revision']},ok=False)
    sub(source,ok=False)
    assert detail(subscription)['membershipTypeName']=='AT-MTYPE-原名'
    update_sub(subscription);assert detail(subscription)['membershipTypeId']==source
    new_sub=sub(renamed['id']);assert detail(new_sub)['membershipTypeName']=='AT-MTYPE-新名'
    filters=req('GET',M+'/filter-options');assert {'name':'AT-MTYPE-原名','state':None} in filters and {'name':'AT-MTYPE-新名','state':None} in filters
    assert [x['id'] for x in req('POST',M+'/list',body={'membershipTypeName':'AT-MTYPE-原名','sortType':1,'sortWay':2,'expiryType':1})]==[subscription]
    update_sub(subscription,membershipTypeChange={'sourceId':renamed['id']});assert detail(subscription)['membershipTypeName']=='AT-MTYPE-新名'
    req('PUT',M+'/update',body={'id':subscription,'membershipName':'空变更','billingCycle':1,'startDate':'2026-10-21','membershipTypeChange':None},ok=False)
    req('PUT',M+'/update',body={'id':subscription,'membershipName':'空来源','billingCycle':1,'startDate':'2026-10-21','membershipTypeChange':{'sourceId':None}},ok=False)
    req('PUT',M+'/update',body={'id':subscription,'membershipName':'旧编码变更','billingCycle':1,'startDate':'2026-10-21','membershipType':99},ok=False)
    current=next(x for x in items()['items'] if x['id']==renamed['id'])
    req('DELETE',D+f"/items/{renamed['id']}?revision={current['revision']}")
    update_sub(subscription);assert detail(subscription)['membershipTypeName']=='AT-MTYPE-新名'
    sub(renamed['id'],ok=False)
    detail(subscription,'b',False)
    req('PUT',M+'/update','b',{'id':subscription,'membershipName':'越权','billingCycle':1,'startDate':'2026-10-21'},False)
    req('DELETE',M+f'/delete?id={subscription}','b',ok=False)
    assert req('POST',M+'/list','b',{})==[]
    print('PASS revision/scopeVersion, Unicode, duplicate disabled names, source validity/ownership, immutable rename/delete snapshots, omit versus explicit change, legacy compatibility and owner isolation')
    sql("INSERT INTO bookkeeping_membership_subscription(user_id,membership_type,membership_name,billing_cycle,start_date) VALUES(40001,77,'AT-MTYPE-待核对',1,'2026-10-21');")
    pending=req('POST',M+'/list',body={'membershipTypeState':'PENDING'});assert len(pending)==1 and pending[0]['membershipTypeSnapshotStatus']==0
    req('POST',M+'/list',body={'membershipTypeState':'PENDING','membershipTypeName':'AT-MTYPE-新名'},ok=False)
    assert {'name':None,'state':'PENDING'} in req('GET',M+'/filter-options')
    for path in ['/auth-service/dict/getDictListByType?dictType=4004','/auth-service/dict/detail?id='+str(source)]:req('GET',path,ok=False)
    # Real family category paths still function for owner/member; 4004 remains excluded from them.
    assert items('b','CATEGORY','FAMILY')['canManage'] is True
    assert items('c','CATEGORY','FAMILY')['canManage'] is False
    print('PASS historical PENDING/name filters, legacy read/detail guard and original category family roles')
    # Destructive missing-family-state fixture stays in this private DB only, never the user environment.
    sql('DELETE FROM bookkeeping_family_dict_state WHERE family_group_id='+str(group)+';')
    assert items('b')['canManage'] and items('c')['canManage']
    assert req('GET',D+'/options?type=MEMBERSHIP_TYPE','c')
    req('GET',D+'/options?type=CATEGORY','c',ok=False)
    print('PASS missing family initialization does not affect membership personal reads/writes; original categories retain guard')
    # For native/browser UI evidence, login normally again and keep only the synthetic response privately.
    response=req('POST','/auth-service/login/password','a',creds['a'])
    private=a.credentials.parent/'ui-user.json';private.write_text(json.dumps(response));os.chmod(private,0o600)
    print('ALL ISOLATED API CHECKS PASSED;',count,'asserted requests; fixtures retained only until UI verification/owned-instance cleanup')
finally:
    creds.clear();tokens.clear()
