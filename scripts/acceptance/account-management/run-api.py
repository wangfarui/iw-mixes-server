#!/usr/bin/env python3
"""Loopback API acceptance; reads an existing login token from a private file, never logs it.
Creates uniquely named account/category fixtures and cleans up only those fixtures.
"""
import json,urllib.request,urllib.parse,uuid,sys
from pathlib import Path
base='http://127.0.0.1:18003'
token=Path(sys.argv[1]).read_text().strip()
prefix='AC-'+uuid.uuid4().hex[:8]
root='/auth-service/application/account'
cat=root+'/categories'
results=[]; accounts=[]; codes=[]
def api(method,path,body=None,deny=False):
 req=urllib.request.Request(base+path,method=method,headers={'Content-Type':'application/json','iwtoken':token},data=None if body is None else json.dumps(body).encode())
 with urllib.request.urlopen(req,timeout=20) as response: value=json.load(response)
 if deny:
  assert value.get('code')!=200, 'Expected rejection: '+path.split('?')[0]
 else:
  assert value.get('code')==200, path.split('?')[0]+': '+str(value.get('message'))
 return value.get('data')
def snapshot(): return api('GET',cat)
def item(code): return next(i for i in snapshot()['items'] if i['code']==code)
def meta(row): return {'revision':row['revision'],'scopeVersion':snapshot()['scopeVersion']}
def result(name): results.append({'case':name,'status':'PASS'});print('PASS',name,flush=True)
def create_category(name):
 response=api('POST',cat,{'name':name,'scopeVersion':snapshot()['scopeVersion']})['item']; codes.append(response['code']);return response
try:
 first=create_category(prefix+'工作'); second=create_category(prefix+'开发'); result('create categories')
 account=api('POST',root+'/add',{'name':prefix+'应用','account':'demo_acceptance','address':'https://'+prefix+'.example.com','type':first['code'],'password':'sample-only-password','remark':'temporary acceptance fixture'})
 accounts.append(account)
 page=api('POST',root+'/page',{'currentPage':1,'pageSize':10,'keyword':prefix+'应用','type':first['code']})
 assert page['total']==1 and page['records'][0]['typeName']==prefix+'工作'
 page=api('POST',root+'/page',{'currentPage':1,'pageSize':10,'keyword':prefix+'.example.com'})
 assert page['total']==1;result('keyword name OR address + category AND')
 row=item(first['code']);assert row['accountCount']==1
 api('DELETE',cat+'/'+str(row['id'])+'?'+urllib.parse.urlencode(meta(row)),deny=True);result('reject deleting referenced category')
 stale=meta(row)
 api('PUT',cat+'/'+str(row['id'])+'/name',{**stale,'name':prefix+'办公'})
 detail=api('GET',root+'/detail?id='+str(account));assert detail['typeName']==prefix+'办公';result('rename updates account display, keeps type code')
 api('PUT',cat+'/'+str(row['id'])+'/status',{**stale,'enabled':False},deny=True);result('stale classification version rejected')
 row=item(first['code']);api('PUT',cat+'/'+str(row['id'])+'/status',{**meta(row),'enabled':False})
 assert api('GET',root+'/detail?id='+str(account))['typeName']==prefix+'办公'
 api('POST',root+'/add',{'name':prefix+'不应保存','type':first['code']},deny=True)
 api('PUT',root+'/update',{'id':account,'name':prefix+'应用','account':'demo_acceptance','type':first['code'],'remark':'updated without selecting again','updatePassword':False})
 assert api('GET',root+'/viewPassword?id='+str(account))=='sample-only-password';result('disabled preserved, new selection denied, unchanged password preserved')
 api('POST',cat,{'name':prefix+'办公','scopeVersion':snapshot()['scopeVersion']},deny=True);result('disabled duplicate rejected')
 before=snapshot();order=[i['id'] for i in before['items']]
 api('PUT',cat+'/order',{'ids':list(reversed(order)),'scopeVersion':before['scopeVersion']})
 assert [i['id'] for i in snapshot()['items']]==list(reversed(order))
 after=snapshot();api('PUT',cat+'/order',{'ids':[order[0],order[0]],'scopeVersion':after['scopeVersion']},deny=True)
 api('PUT',cat+'/order',{'ids':order,'scopeVersion':after['scopeVersion']});result('sort exact set, reorder and restore')
 api('PUT',root+'/update',{'id':account,'name':prefix+'应用','type':None,'updatePassword':False})
 assert api('GET',root+'/detail?id='+str(account))['type']==0
 assert api('POST',root+'/page',{'currentPage':1,'pageSize':10,'keyword':prefix+'应用','unclassified':True})['total']==1;result('clear classification + unclassified query')
 api('POST','/auth-service/dict/add',{'dictType':2010,'dictName':prefix+'旧入口'},deny=True)
 api('GET','/auth-service/dict/detail?id='+str(first['id']),deny=True)
 api('POST','/auth-service/dict/page',{'currentPage':1,'pageSize':10,'dictType':2010},deny=True);result('legacy generic classification endpoints blocked')
 api('PUT',root+'/update',{'id':account,'name':prefix+'应用','type':second['code'],'updatePassword':False})
 api('DELETE',root+'/delete?id='+str(account));accounts.remove(account);result('account delete')
finally:
 for account in accounts:
  try: api('DELETE',root+'/delete?id='+str(account));accounts.remove(account)
  except Exception: results.append({'case':'fixture account cleanup','status':'FAILED'})
 for code in codes:
  try:
   row=item(code);api('DELETE',cat+'/'+str(row['id'])+'?'+urllib.parse.urlencode(meta(row)))
  except Exception: results.append({'case':'fixture category cleanup','status':'FAILED'})
 out=Path(sys.argv[2]);out.parent.mkdir(parents=True,exist_ok=True)
 out.write_text(json.dumps({'environment':'loopback 18003, real MySQL','fixturePrefix':prefix,'results':results,'remainingAccounts':len(accounts)},ensure_ascii=False,indent=2))
 print('Fixture cleanup finished; report:',out)
