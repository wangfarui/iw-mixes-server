const fs = require('node:fs');
const assert = require('node:assert/strict');
const a = require('/tmp/iw-account-tooling/node_modules/miniprogram-automator');
let mp;
const timer = setTimeout(() => { console.error('native smoke timeout'); process.exit(2); }, 55000);
(async () => {
 mp = await a.connect({wsEndpoint:'ws://127.0.0.1:9437'});
 await mp.mockWxMethod('request', function(options) {
  const url = options.url;
  let data;
  if (url.includes('/points/task/plan/convertDate')) data={solarDate:'2023-02-20',lunarYear:2023,lunarMonth:2,lunarDay:1,leapMonth:false,text:'农历2023年二月初一'};
  else if (url.includes('/points/task/plan/calendar')) data=[{month:2,leap:false,label:'二月',days:30},{month:2,leap:true,label:'闰二月',days:29},{month:3,leap:false,label:'三月',days:29}];
  else if (url.includes('/points/task/plan/preview')) {
   const lunar=options.data.calendarType==='LUNAR';
   data={calendarType:lunar?'LUNAR':'SOLAR',anchorDate:'2023-02-20',ruleText:lunar?'每个农历月初一（含闰月）':'每月20日',occurrences:[{planDate:'2023-02-20',planDateText:lunar?'农历2023年二月初一（公历 2023-02-20）':'公历 2023-02-20',generateDate:'2023-02-20',deadlineDate:'2023-02-20'},{planDate:'2023-03-22',planDateText:'农历2023年闰二月初一（公历 2023-03-22）',generateDate:'2023-03-22',deadlineDate:'2023-03-22'},{planDate:'2023-04-20',planDateText:'农历2023年三月初一（公历 2023-04-20）',generateDate:'2023-04-20',deadlineDate:'2023-04-20'}]};
  } else {if(options.fail) options.fail({errMsg:'isolated UI smoke: requests blocked'}); return;}
  return {statusCode:200,data:{code:200,data}};
 });
 const page = await mp.reLaunch('/pagesPoints/points/task-plan-add');
 assert.equal(page.path,'pagesPoints/points/task-plan-add');
 assert.equal(await page.data('formData.calendarType'),'SOLAR');
 const input=await page.$('.form-input');await input.input('农历界面验证（模拟日期）');
 await page.callMethod('onPlanDateChange',{detail:{value:'2023-02-20'}});
 await page.callMethod('onPlanCycleChange',{detail:{value:2}});
 await (await page.$('.segment[data-value="LUNAR"]')).tap();
 await page.waitFor(600);
 console.log(JSON.stringify({calendar:await page.data('formData.calendarType'),previewError:await page.data('previewError'),calendarError:await page.data('calendarError'),converting:await page.data('converting')}));
 assert.equal(await page.data('formData.calendarType'),'LUNAR');
 assert.equal(await page.data('lunarMonthText'),'二月');
 const text=await (await page.$('.preview-rule')).text();assert.ok(text.includes('含闰月'));

 await mp.pageScrollTo(1300); await page.waitFor(300);

 assert.ok((await (await page.$('.plan-preview')).text()).includes('闰二月'));
 await mp.pageScrollTo(0);await page.waitFor(200);
 const pickers=await page.$$('.lunar-fields picker');await pickers[1].trigger('change',{value:1});
 await page.waitFor(400);assert.equal(await page.data('formData.leapMonth'),true);
 console.log('PASS: native WXML compilation, solar default, Chinese input, calendar switch, leap-month picker binding, three-period preview. API responses mocked; no data written.');
 fs.writeFileSync('/tmp/iw-lunar-native/result.json',JSON.stringify({status:'PASS',mode:'native simulator with mocked read APIs',realBusinessApi:false,dataWrites:0},null,2));
})().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(async()=>{if(mp){await mp.restoreWxMethod('request').catch(()=>{});await mp.disconnect().catch(()=>{});}clearTimeout(timer);});
