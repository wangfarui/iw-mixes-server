#!/usr/bin/env python3
"""Local API acceptance using one explicitly supplied test account. Credentials remain in memory.
Creates uniquely labelled fixtures and preserves them for UI inspection. Does not claim five-role coverage.
"""
import argparse
import fcntl
import getpass
import json
import os
import re
import sys
import urllib.request
import warnings
from decimal import Decimal
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--execute', action='store_true', required=True)
p.add_argument('--credentials-stdin', action='store_true', help='Read one JSON account/password object from a non-echoing pipe')
p.add_argument('--run-id', required=True)
p.add_argument('--month', required=True)
p.add_argument('--state-dir', type=Path, required=True)
a = p.parse_args()
assert re.fullmatch(r'[A-Za-z0-9_-]{1,12}', a.run_id)
assert re.fullmatch(r'\d{4}-(0[1-9]|1[0-2])', a.month)
a.state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
state_path = a.state_dir / (a.run_id + '.json')
lock = (a.state_dir / (a.run_id + '.lock')).open('w')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
state = json.loads(state_path.read_text()) if state_path.exists() else {'runId':a.run_id,'month':a.month,'fixtures':{},'completed':[],'results':[]}
assert state['runId']==a.run_id and state['month']==a.month
f = state['fixtures']
token = None
active = 'LOGIN'
base = 'http://127.0.0.1:18000'
dict_api = '/bookkeeping-service/bookkeeping/dictionaries'
records = '/bookkeeping-service/bookkeeping/records'
first = a.month + '-01'
source = 'SELF-' + a.run_id
old_name = '自验分类-' + a.run_id
new_name = '自验新分类-' + a.run_id
tag_name = '自验标签-' + a.run_id

def save():
    temp = state_path.with_suffix('.tmp')
    with temp.open('w') as out:
        os.chmod(temp, 0o600)
        json.dump(state,out,ensure_ascii=False,indent=2)
    temp.replace(state_path)

def api(method, path, body=None, denied=None):
    headers = {'Content-Type':'application/json'}
    if token: headers['iwtoken'] = token
    req = urllib.request.Request(base+path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    with urllib.request.urlopen(req,timeout=20) as response:
        data = json.load(response)
    if denied:
        assert data.get('code')!=200 and re.search(denied,str(data.get('message',''))), 'Expected business denial: '+path.split('?')[0]+' code='+str(data.get('code'))+' reason='+str(data.get('message',''))[:100]
        return None
    assert data.get('code')==200, 'API failed: '+path.split('?')[0]+' code='+str(data.get('code'))
    return data.get('data')

def fixture(key, action):
    if key not in f:
        f[key] = action()
        save()
    return f[key]

def step(key, detail, action):
    global active
    active = key
    if key in state['completed']:
        print(json.dumps({'scene':key,'status':'SKIPPED_PREVIOUS'}),flush=True)
        return
    action()
    state['completed'].append(key)
    result = {'scene':key,'status':'PASS','detail':detail}
    state['results'].append(result)
    save()
    print(json.dumps(result,ensure_ascii=False),flush=True)

def create(kind,scope,name):
    return api('POST',dict_api+'/items',{'type':kind,'scope':scope,'name':name})

def query(**kwargs):
    return {'currentMonth':first,'statisticsType':1,'isSearchAll':0,'queryOnlyMyself':1,**kwargs}

def total(**kwargs):
    return Decimal(str(api('POST','/bookkeeping-service/bookkeeping/consume/totalStatistics',query(**kwargs))['totalAmount']))

def bill(category, amount, tags, key):
    return {'recordCategory':1,'recordDate':first,'recordSource':source,'recordTypeId':category,
            'recordTags':tags,'amount':amount,'shared':0,'isStatistics':1,'isExcitationRecord':0,'remark':source+':'+key}

def detail():
    return api('GET',records+'/detail?id='+str(f['bill1']))

def edit(extra):
    return api('PUT',records+'/update',{'id':f['bill1'],'recordCategory':1,'recordDate':first,'amount':1.01,
                                      'isStatistics':1,'remark':source+':ordinary-edit',**extra})
try:
    if a.credentials_stdin:
        assert not sys.stdin.isatty(), 'Credentials stdin requires a pipe, never an echoing terminal'
        credentials = json.loads(sys.stdin.readline())
        account, password = credentials['account'], credentials['password']
        del credentials
    else:
        warnings.simplefilter('error', getpass.GetPassWarning)
        account = getpass.getpass('Local test account (not echoed): ')
        password = getpass.getpass('Local password (not echoed): ')
    user = api('POST','/auth-service/login/password',{'account':account,'password':password})
    del account,password
    token = user['tokenValue']
    uid = user['id']
    if 'userId' in state: assert state['userId']==uid
    state['userId']=uid
    save()
    del user
    group = api('GET','/auth-service/family/group/myGroup')
    print(json.dumps({'scene':'LOGIN','status':'PASS','userId':uid,'familyId':group.get('id') if group else None}),flush=True)
    step('PRE-EMPTY','Dedicated test month has no personal expenses',lambda: (None if total()==0 else (_ for _ in ()).throw(AssertionError('Nonempty test month'))))
    def manage():
        assert api('GET',dict_api+'/items?type=CATEGORY&scope=PERSONAL')['canManage']
        assert api('GET',dict_api+'/items?type=CATEGORY&scope=FAMILY')['canManage']
        fixture('category',lambda:create('CATEGORY','PERSONAL',old_name))
        fixture('familyCategory',lambda:create('CATEGORY','FAMILY',old_name))
        fixture('tag',lambda:create('EXPENSE_TAG','PERSONAL',tag_name))
        fixture('incomeTag',lambda:create('INCOME_TAG','PERSONAL','自验收入-'+a.run_id))
        assert f['category']['id']!=f['familyCategory']['id']
        api('POST',dict_api+'/items',{'type':'CATEGORY','scope':'PERSONAL','name':old_name},denied='重复|存在')
        opts=api('GET',dict_api+'/options?type=CATEGORY')
        matches=[row for row in opts if row['name']==old_name]
        assert len(matches)==1 and matches[0]['id']==f['category']['id']
    step('MANAGE','Owner/parent can manage personal/family; duplicate rejected; personal option preferred',manage)
    def create_bill():
        fixture('bill1',lambda:api('POST',records+'/add',bill(f['category']['id'],1.01,[f['tag']['id']],'bill1')))
        row=detail()
        assert row['recordTypeName']==old_name and row['recordTypeId']==f['category']['id']
        assert row['recordTagSnapshots'][0]['name']==tag_name
        assert total()==Decimal('1.01')
        fixture('action',lambda:api('POST','/bookkeeping-service/bookkeeping/actions/add',{
            'recordCategory':1,'recordSource':source,'recordTypeId':f['category']['id'],'recordTags':[f['tag']['id']]}))
        fixture('budget',lambda:api('POST','/bookkeeping-service/bookkeeping/budget/add',{
            'budgetType':11,'recordTypeId':f['category']['id'],'budgetAmount':9.99,'rewardPoints':0,'punishPoints':0}))
        api('POST','/bookkeeping-service/bookkeeping/budget/add',{
            'budgetType':11,'recordTypeId':f['familyCategory']['id'],'budgetAmount':9.99},denied='重复|存在')
    step('RECORD','Bill snapshots, personal amount, quick config and exact-name budget duplicate',create_bill)
    def rename():
        fixture('newCategory',lambda:api('PUT',dict_api+f"/items/{f['category']['id']}/name",{'name':new_name,'revision':f['category']['revision']})['item'])
        fixture('newTag',lambda:api('PUT',dict_api+f"/items/{f['tag']['id']}/name",{'name':'自验新标签-'+a.run_id,'revision':f['tag']['revision']})['item'])
        assert f['newCategory']['id']!=f['category']['id'] and detail()['recordTypeName']==old_name
        assert detail()['recordTagSnapshots'][0]['name']==tag_name
        api('PUT',dict_api+f"/items/{f['category']['id']}/name",{'name':new_name,'revision':f['category']['revision']},denied='版本|变化|删除')
        edit({})
        assert detail()['recordTypeName']==old_name and detail()['recordTagSnapshots'][0]['name']==tag_name
        action=api('GET','/bookkeeping-service/bookkeeping/actions/detail?id='+str(f['action']))
        assert action['available'] and action['recordTypeId']==f['newCategory']['id']
        assert action['recordTagSnapshots'][0]['id']==f['newTag']['id']
        budget=api('GET','/bookkeeping-service/bookkeeping/budget/detail?id='+str(f['budget']))
        assert budget['recordTypeName']==old_name and budget['recordTypeId']==f['category']['id']
    step('RENAME','New IDs; historical bill/budget snapshots unchanged; quick config follows successors; stale revision rejected',rename)
    def statistics():
        fixture('bill2',lambda:api('POST',records+'/add',bill(f['newCategory']['id'],2.02,[f['newTag']['id']],'bill2')))
        assert total()==Decimal('3.03')
        assert total(categoryNames=[old_name])==Decimal('1.01')
        assert total(categoryNames=[new_name])==Decimal('2.02')
        assert total(tagNames=[tag_name])==Decimal('1.01')
        assert total(tagNames=['自验新标签-'+a.run_id])==Decimal('2.02')
        rows=api('POST','/bookkeeping-service/bookkeeping/consume/pieChartStatistics',query(isQueryLastMonth=False))
        assert len([r for r in rows if r['recordTypeName']==old_name])==1
        assert len([r for r in rows if r['recordTypeName']==new_name])==1
        page=api('POST',records+'/page',{'currentPage':1,'pageSize':20,'recordStartDate':first,'recordEndDate':a.month+'-28','queryOnlyMyself':1,'categoryNames':[old_name]})
        assert page['total']==1 and page['records'][0]['id']==f['bill1']
    step('STATISTICS','Historical/new category and tag filters and pie groups retain exact amounts',statistics)
    def invalid():
        before=total()
        api('POST',records+'/add',bill(f['newCategory']['id'],0.01,[f['incomeTag']['id']],'wrong-tag'),denied='标签|来源|类型|选项已失效|无权使用')
        old=bill(None,0.01,[],'old-client');old.pop('recordTypeId');old['recordType']=1
        api('POST',records+'/add',old,denied='更新客户端|来源ID')
        assert total()==before
    step('INVALID','Wrong tag type and old code-only client are rejected without adding a bill',invalid)
    def clear():
        edit({'dictionaryChanges':{'categoryId':None,'tagIds':[]}})
        row=detail()
        assert row.get('recordTypeId') is None and row.get('recordTypeName') is None and row.get('recordTagSnapshots')==[]
        assert total(categoryState='UNCATEGORIZED')==Decimal('1.01')
        assert total(categoryNames=[old_name])==0
        assert total()==Decimal('3.03')
    step('CLEAR','Explicit category/tag clearing persists; uncategorized filter is accurate',clear)
    def disable():
        item=fixture('disabledTag',lambda:api('PUT',dict_api+f"/items/{f['newTag']['id']}/status",{'enabled':False,'revision':f['newTag']['revision']}))
        assert not api('GET','/bookkeeping-service/bookkeeping/actions/detail?id='+str(f['action']))['available']
        row=api('GET',records+'/detail?id='+str(f['bill2']))
        assert row['recordTagSnapshots'][0]['name']=='自验新标签-'+a.run_id
        api('POST',dict_api+'/items',{'type':'EXPENSE_TAG','scope':'PERSONAL','name':'自验新标签-'+a.run_id},denied='重复|存在')
        fixture('enabledTag',lambda:api('PUT',dict_api+f"/items/{item['id']}/status",{'enabled':True,'revision':item['revision']}))
        assert api('GET','/bookkeeping-service/bookkeeping/actions/detail?id='+str(f['action']))['available']
    step('STATUS','Disabled source blocks future quick action but preserves old bill; enable restores it',disable)
    # Always reread final invariants, including on resume; skipped write phases are not rerun evidence.
    active = 'FINAL-READ'
    assert total()==Decimal('3.03')
    assert total(categoryState='UNCATEGORIZED')==Decimal('1.01')
    assert total(categoryNames=[new_name])==Decimal('2.02')
    assert total(categoryNames=[old_name])==0
    assert total(tagNames=['自验新标签-'+a.run_id])==Decimal('2.02')
    assert detail().get('recordTypeId') is None and detail()['recordTagSnapshots']==[]
    second=api('GET',records+'/detail?id='+str(f['bill2']))
    assert second['recordTypeName']==new_name and second['recordTagSnapshots'][0]['name']=='自验新标签-'+a.run_id
    action=api('GET','/bookkeeping-service/bookkeeping/actions/detail?id='+str(f['action']))
    assert action['available'] and action['recordTypeId']==f['newCategory']['id']
    budget=api('GET','/bookkeeping-service/bookkeeping/budget/detail?id='+str(f['budget']))
    assert budget['recordTypeName']==old_name and budget['recordTypeId']==f['category']['id']
    rows=api('POST','/bookkeeping-service/bookkeeping/consume/pieChartStatistics',query(isQueryLastMonth=False))
    assert sum(Decimal(str(row['amount'])) for row in rows)==Decimal('3.03')
    print(json.dumps({'scene':'FINAL-READ','status':'PASS','detail':'Fresh read of final totals, filters, snapshots, quick config and budget after restart'}),flush=True)
    print(json.dumps({'status':'COMPLETE','stateFile':str(state_path),'expenseFixtures':2,'expenseAmount':'3.03','fixturesRetained':True}),flush=True)
except Exception as error:
    # Never serialize credentials, arbitrary requests/responses or a remote error body.
    message=str(error) if isinstance(error,AssertionError) else type(error).__name__
    result={'scene':active,'status':'FAIL','detail':message}
    state['results'].append(result);save()
    print(json.dumps(result,ensure_ascii=False),flush=True)
    sys.exit(1)
finally:
    token=None
    fcntl.flock(lock,fcntl.LOCK_UN)
    lock.close()
