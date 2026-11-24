const assert = require('node:assert/strict');
const fs = require('node:fs');
const H = require('./hud.js');

let state = H.initialState('Long response. '.repeat(300));
state = H.reducer(state, {type:'page',page:2});
const response=state.output;
state=H.reducer(state,{type:'listening',time:4});
assert.equal(state.output,response);
assert.equal(state.page,2);
assert.equal(state.avatar,'listening');
assert.equal(H.reducer(state,{type:'emote',avatar:'happy'}),state);

state=H.reducer(state,{type:'turn',turnId:'a',time:5});
state=H.reducer(state,{type:'event',event:'tool.started',status:'delegating',turnId:'a',time:6});
assert.equal(state.avatar,'delegating');
state=H.reducer(state,{type:'delta',text:'New ',turnId:'a'});
state=H.reducer(state,{type:'delta',text:'reply',turnId:'a'});
assert.equal(state.output,response,'Partial streamed text must not erase a readable response.');
state=H.reducer(state,{type:'result',turnId:'a',time:7});
assert.equal(state.output,'New reply');
assert.equal(state.page,0);
assert.equal(state.status,'speaking');
assert.equal(state.avatarSince,7);
state=H.reducer(state,{type:'listening',time:7.1});
const listening=state;
assert.equal(H.reducer(state,{type:'done',turnId:'a'}),listening,'Late completion must not interrupt voice capture.');
assert.equal(H.reducer(state,{type:'response',turnId:'a',text:'late'}),listening);
state=H.reducer(state,{type:'turn',turnId:'b'});
assert.equal(H.reducer(state,{type:'response',turnId:'a',text:'late'}),state);
state=H.reducer(state,{type:'error',message:'Stream interrupted',turnId:'b'});
assert.equal(state.output,'New reply');
assert.equal(state.error,'Stream interrupted');
assert.equal(state.status,'error');

// Avatar pack: the bundled placeholder (same schema the Android app and simulator read).
const pack=JSON.parse(fs.readFileSync(__dirname+'/../simulator/avatar/default/manifest.json'));
assert.equal(pack.schema,1);
assert.equal(H.resolveState(pack,'speaking'),'speaking');
assert.equal(H.resolveState(pack,'compressing'),'working');
assert.equal(H.resolveState(pack,'no_such_state'),'idle');
assert.equal(H.avatarState(17),'happy');
assert.equal(H.avatarState('17'),'happy');
const loop={playback:'loop',frames:[{cell:0,ms:350},{cell:1,ms:350},{cell:2,ms:450}]};
assert.deepEqual([0,.4,.8,1.2].map(t=>H.animationFrame(loop,t)),[0,1,2,0]);
assert.deepEqual([0,.4,.8,1.2].map(t=>H.animationFrame({...loop,playback:'once'},t)),[0,1,2,2]);
assert.equal(H.animationFrame({...loop,playback:'bounded_loop'},2.4),0);
assert.equal(H.animationFrame({...loop,playback:'bounded_loop'},3.1),2);
let emoted=H.reducer(H.initialState(),{type:'emote',avatar:'happy',time:1});
assert.equal(emoted.avatar,'happy');
assert.equal(H.reducer(H.initialState(),{type:'emote',animationId:44,time:1}).avatar,'waving');
// A synthetic scenario (fictional; no live operations).
const scenario={beats:[
  {start:0,duration:4,avatar:'idle',status:'idle',response:'Ready when you are.',pageIndex:0},
  {start:4,duration:5,avatar:'listening',status:'listening',response:'Ready when you are.',pageIndex:0},
  {start:9,duration:3,avatar:'searching',status:'searching',response:'Looking that up.',pageIndex:0},
  {start:12,duration:9,avatar:'speaking',status:'speaking',response:'Here is a longer answer. '.repeat(40),pageIndex:1,responsePages:['First page.','Second page.']},
  {start:21,duration:3,animationId:17,status:'idle',response:'Done.',pageIndex:0},
]};
const drawn=[];
const ctx={fillRect(...values){values.forEach(value=>assert(Number.isInteger(value)));},drawImage(...a){drawn.push(a);}};
const atlas={};
for(const beat of scenario.beats){
  for(const page of beat.responsePages||[])assert.equal(H.paginate(page).length,1);
  const info=H.renderHUD(ctx,{...H.initialState(beat.response),...beat,output:beat.response,page:beat.pageIndex,avatarSince:beat.start},pack,atlas,beat.start+.1);
  assert(info.page<info.pages);
}
assert.equal(drawn.length,scenario.beats.length);
for(const d of drawn){assert.equal(d[0],atlas);assert.deepEqual(d.slice(3,9),[pack.cell.w,pack.cell.h,4,6,192,288]);}
assert.equal(ctx.imageSmoothingEnabled,false);
const html=fs.readFileSync(__dirname+'/index.html','utf8');
for(const match of html.matchAll(/<script>([\s\S]*?)<\/script>/g))new Function(match[1]);
console.log('HUD tests passed: pagination, listening preservation, stream lifecycle, stale turns, error retention, native rendering, JS syntax, avatar pack.');
