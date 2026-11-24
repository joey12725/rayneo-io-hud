// Development only: preserve the approved simulator's exact fixtures and font rows.
const fs=require('fs'), vm=require('vm'), path=require('path');
const src=fs.readFileSync(path.join(__dirname,'../../simulator/index.html'),'utf8').replace(/\r/g,'');
const between=(a,b)=>src.slice(src.indexOf(a),src.indexOf(b,src.indexOf(a)));
const code=[
 between('const FONT5=','const F=()'),
 between('const S=','// =====================================================================\n// UI building blocks'),
 between('const JOY_SAMPLES=','let joyN='),
 between('const SAMPLE_MSG=','let smN=').replace(/;\s*$/,'' )+';',
 between('const SPOT_TRACK=','function spotPlay'),
 between('const CHANNELS=','function ytV'),
 between('function series(','function spark('),
 between('const MEMO_TITLES=','let mt=').replace(/;\s*$/,'')+';',
 'JSON.stringify({FONT5,FONTS,S,joy,chats,T3,M,ABS,SPOT_RECENT,YT,DEVICES,PEOPLE,DOCS,SCRIPT,CAL,REPORT,JOY_SAMPLES,JOY_ANS,SAMPLE_MSG,SPOT_TRACK,CHANNELS,YT_LISTS,HEALTH,MEMO_TITLES})'
].join('\n');
const out=vm.runInNewContext(code,{Date,Math,ANIM:{waving:44}});
fs.writeFileSync(path.join(__dirname,'../app/src/main/assets/demo.json'),JSON.stringify(JSON.parse(out),null,2));

