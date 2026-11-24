/* Native 600x300 monochrome Hermes HUD with a bring-your-own avatar pack. No canvas text or browser font rasterization. */
(function (root) {
  'use strict';
  const FONT = {
    'A':['01110','10001','10001','11111','10001','10001','10001'], 'B':['11110','10001','10001','11110','10001','10001','11110'],
    'C':['01111','10000','10000','10000','10000','10000','01111'], 'D':['11110','10001','10001','10001','10001','10001','11110'],
    'E':['11111','10000','10000','11110','10000','10000','11111'], 'F':['11111','10000','10000','11110','10000','10000','10000'],
    'G':['01111','10000','10000','10111','10001','10001','01111'], 'H':['10001','10001','10001','11111','10001','10001','10001'],
    'I':['11111','00100','00100','00100','00100','00100','11111'], 'J':['00111','00010','00010','00010','10010','10010','01100'],
    'K':['10001','10010','10100','11000','10100','10010','10001'], 'L':['10000','10000','10000','10000','10000','10000','11111'],
    'M':['10001','11011','10101','10101','10001','10001','10001'], 'N':['10001','11001','11001','10101','10011','10011','10001'],
    'O':['01110','10001','10001','10001','10001','10001','01110'], 'P':['11110','10001','10001','11110','10000','10000','10000'],
    'Q':['01110','10001','10001','10001','10101','10010','01101'], 'R':['11110','10001','10001','11110','10100','10010','10001'],
    'S':['01111','10000','10000','01110','00001','00001','11110'], 'T':['11111','00100','00100','00100','00100','00100','00100'],
    'U':['10001','10001','10001','10001','10001','10001','01110'], 'V':['10001','10001','10001','10001','10001','01010','00100'],
    'W':['10001','10001','10001','10101','10101','10101','01010'], 'X':['10001','10001','01010','00100','01010','10001','10001'],
    'Y':['10001','10001','01010','00100','00100','00100','00100'], 'Z':['11111','00001','00010','00100','01000','10000','11111'],
    '0':['01110','10001','10011','10101','11001','10001','01110'], '1':['00100','01100','00100','00100','00100','00100','01110'],
    '2':['01110','10001','00001','00010','00100','01000','11111'], '3':['11110','00001','00001','01110','00001','00001','11110'],
    '4':['00010','00110','01010','10010','11111','00010','00010'], '5':['11111','10000','10000','11110','00001','00001','11110'],
    '6':['01110','10000','10000','11110','10001','10001','01110'], '7':['11111','00001','00010','00100','01000','01000','01000'],
    '8':['01110','10001','10001','01110','10001','10001','01110'], '9':['01110','10001','10001','01111','00001','00001','01110'],
    '.':['00000','00000','00000','00000','00000','00110','00110'], ',':['00000','00000','00000','00000','00110','00100','01000'],
    ':':['00000','00110','00110','00000','00110','00110','00000'], ';':['00000','00110','00110','00000','00110','00100','01000'],
    '!':['00100','00100','00100','00100','00100','00000','00100'], '?':['01110','10001','00001','00010','00100','00000','00100'],
    "'":['00100','00100','00000','00000','00000','00000','00000'], '"':['01010','01010','00000','00000','00000','00000','00000'],
    '-':['00000','00000','00000','11111','00000','00000','00000'], '_':['00000','00000','00000','00000','00000','00000','11111'],
    '/':['00001','00010','00010','00100','01000','01000','10000'], '\\':['10000','01000','01000','00100','00010','00010','00001'],
    '<':['00010','00100','01000','10000','01000','00100','00010'], '>':['01000','00100','00010','00001','00010','00100','01000'],
    '(' :['00010','00100','01000','01000','01000','00100','00010'], ')':['01000','00100','00010','00010','00010','00100','01000'],
    '[':['01110','01000','01000','01000','01000','01000','01110'], ']':['01110','00010','00010','00010','00010','00010','01110'],
    '+':['00000','00100','00100','11111','00100','00100','00000'], '=':['00000','00000','11111','00000','11111','00000','00000'],
    '*':['00000','10101','01110','11111','01110','10101','00000'], '#':['01010','01010','11111','01010','11111','01010','01010'],
    '%':['11001','11010','00100','01000','10110','00110','00000'], '&':['01100','10010','10100','01000','10101','10010','01101'],
    '@':['01110','10001','10111','10101','10111','10000','01110'], '|':['00100','00100','00100','00100','00100','00100','00100'],
    ' ':['00000','00000','00000','00000','00000','00000','00000']
  };
  // Avatar states: canonical names shared with the Android app and simulator (docs/avatar-packs.md).
  const LEGACY_IDS = ['idle','sleeping','waking','listening','thinking','working','searching','remembering','saving','speaking','waiting','delegating','compressing','asking','interrupted','error','happy','laughing','smirking','playful','heart_eyes','blushing','sad','concerned','skeptical','curious','surprised','facepalm','celebrating','proud','confused','romantic','spicy','nerdy','creative','philosophical','serious','comforting','teaching','debating','secretive','looking_around','stretching','waving','nodding','shaking_head','winking','dancing','coffee','checking_watch'];
  const STATUS_STATES = new Set(['idle','listening','thinking','working','searching','remembering','saving','speaking','waiting','delegating','error']);
  const FALLBACKS = {sleeping:'idle',waking:'idle',listening:'idle',thinking:'idle',speaking:'idle',working:'thinking',searching:'working',remembering:'thinking',saving:'working',waiting:'thinking',delegating:'working',compressing:'working',asking:'thinking',interrupted:'listening',error:'idle',happy:'idle',laughing:'happy',smirking:'happy',playful:'happy',heart_eyes:'happy',blushing:'happy',sad:'error',concerned:'error',skeptical:'thinking',curious:'thinking',surprised:'idle',facepalm:'error',celebrating:'happy',proud:'happy',confused:'thinking',romantic:'happy',spicy:'happy',nerdy:'working',creative:'working',philosophical:'thinking',serious:'idle',comforting:'happy',teaching:'speaking',debating:'speaking',secretive:'idle',looking_around:'idle',stretching:'idle',waving:'happy',nodding:'happy',shaking_head:'idle',winking:'happy',dancing:'celebrating',coffee:'idle',checking_watch:'waiting'};
  const DEFAULT_PACK = '../simulator/avatar/default/manifest.json';
  function statusState(status) { return STATUS_STATES.has(status) ? status : 'idle'; }
  /** An avatar state from a state name or a legacy numeric animation id. */
  function avatarState(value) { return typeof value === 'number' || /^\d+$/.test(String(value)) ? (LEGACY_IDS[Number(value)-1] || 'idle') : String(value || 'idle'); }
  function resolveState(pack,state) {
    const seen=new Set();let s=state;
    while(s!=null&&!seen.has(s)){if(pack.states[s])return s;seen.add(s);s=(pack.fallbacks||{})[s] ?? FALLBACKS[s];}
    return pack.states.idle?'idle':Object.keys(pack.states)[0];
  }
  function clipSeconds(clip) { return clip.frames.reduce((a,f)=>a+f.ms,0)/1000; }
  const GEOMETRY = Object.freeze({width:600,height:300,textX:210,textY:14,columns:31,rows:14,lineHeight:18,scale:2});
  function normalize(text) { return String(text || '').normalize('NFKD').replace(/[\u0300-\u036f]/g,'').replace(/[\u2018\u2019]/g,"'").replace(/[\u201c\u201d]/g,'"').replace(/[\u2013\u2014]/g,'-').replace(/\u2026/g,'...').toUpperCase(); }
  function drawText(ctx,text,x,y,options={}) {
    const scale=options.scale || 2, start=x; ctx.fillStyle=options.color || '#00ff00';
    for(const c of normalize(text)) {
      if(c==='\n') { x=start; y+=options.lineHeight || 9*scale; continue; }
      const rows=FONT[c] || FONT['?'];
      rows.forEach((row,yy)=>{for(let xx=0;xx<5;xx++) if(row[xx]==='1') ctx.fillRect(Math.round(x+xx*scale),Math.round(y+yy*scale),scale,scale);});
      x+=6*scale;
    }
  }
  function paginate(text,columns=GEOMETRY.columns,rows=GEOMETRY.rows) {
    const lines=[];
    for(const paragraph of normalize(text).replace(/\r/g,'').split('\n')) {
      let line='';
      for(let word of paragraph.split(/\s+/).filter(Boolean)) {
        if(line && line.length+1+word.length>columns) { lines.push(line);line=''; }
        while(word.length>columns) { if(line){lines.push(line);line='';} lines.push(word.slice(0,columns));word=word.slice(columns); }
        if(word) line+=(line?' ':'')+word;
      }
      lines.push(line);
    }
    const pages=[];for(let i=0;i<lines.length;i+=rows) pages.push(lines.slice(i,i+rows));
    return pages.length?pages:[[]];
  }
  function initialState(output='Ready when you are.') { return {status:'idle',output,page:0,avatar:'idle',avatarSince:0,turnId:null,emote:null,error:null}; }
  function reducer(state,event) {
    if(event.turnId && state.turnId && event.turnId!==state.turnId && event.type!=='turn') return state;
    if(state.status==='listening' && event.turnId && event.type!=='turn') return state;
    const t=Number(event.time)||0;
    switch(event.type) {
      case 'turn': return {...state,status:'thinking',turnId:event.turnId,emote:null,avatar:'thinking',avatarSince:t,error:null,streamedText:''};
      case 'event': return event.status?reducer(state,{...event,type:'status'}):state;
      case 'delta': return {...state,streamedText:(state.streamedText||'')+(event.text||'')};
      case 'result': return reducer(state,{...event,type:'response',text:event.text ?? state.streamedText ?? ''});
      case 'status': return {...state,status:event.status,avatar:event.avatar ? avatarState(event.avatar) : event.animationId ? avatarState(event.animationId) : statusState(event.status),avatarSince:t,emote:null};
      case 'listening': return {...state,status:'listening',avatar:'listening',avatarSince:t,emote:null};
      case 'response': return {...state,output:event.text ?? event.output ?? '',page:0,status:'speaking',avatar:'speaking',avatarSince:t,emote:null};
      case 'append': return {...state,output:state.output+(event.text||'')};
      case 'page': return {...state,page:Math.max(0,Math.min(paginate(state.output).length-1,event.page ?? (state.page+(event.delta||0))))};
      case 'emote': {if(state.status==='listening') return state; const avatar=avatarState(event.avatar ?? event.animationId); return {...state,emote:{avatar,since:t,hold:!!event.hold},avatar,avatarSince:t};}
      case 'done': return {...state,status:'idle',avatar:'idle',avatarSince:t,emote:null};
      case 'error': return {...state,status:'error',avatar:'error',avatarSince:t,error:event.message||'Connection failed. Try again.'};
      default:return state;
    }
  }
  /** Frame index for a clip: same timing and loop policies as the Android app (docs/avatar-packs.md). */
  function animationFrame(clip,elapsed) {
    const durations=clip.frames.map(f=>f.ms/1000),total=durations.reduce((a,b)=>a+b,0);
    const playback=clip.playback || 'loop';
    const loops=playback==='loop'||playback==='subtle_loop'||(playback==='bounded_loop'&&elapsed<(clip.boundedLoopSeconds||3));
    let local=loops?Math.max(0,elapsed)%total:Math.max(0,elapsed);
    for(let i=0;i<durations.length;i++){if(local<durations[i])return i;local-=durations[i];}
    return durations.length-1;
  }
  /** Draws one frame. `pack` is a schema-v1 avatar manifest and `atlas` its decoded atlas image (or null). */
  function renderHUD(ctx,state,pack,atlas,time=0) {
    ctx.imageSmoothingEnabled=false;ctx.fillStyle='#000000';ctx.fillRect(0,0,600,300);
    if(pack) {
      let name=resolveState(pack,avatarState(state.avatar ?? state.animationId ?? statusState(state.status)));
      let clip=pack.states[name];
      if(state.emote && time-state.emote.since>=clipSeconds(clip) && !state.emote.hold && clip.playback==='once') clip=pack.states[resolveState(pack,statusState(state.status))];
      const cell=clip.frames[animationFrame(clip,time-(state.avatarSince ?? state.animationSince ?? 0))].cell,cols=pack.atlas.cols,{w,h}=pack.cell;
      if(atlas) ctx.drawImage(atlas,(cell%cols)*w,Math.floor(cell/cols)*h,w,h,4,6,192,288);
    }
    const pages=state.responsePages?.length?state.responsePages.flatMap(p=>paginate(p)):paginate(state.output),page=Math.max(0,Math.min(pages.length-1,Number(state.page)||0));
    pages[page].forEach((line,i)=>drawText(ctx,line,210,14+i*18));
    if(pages.length>1) {
      const footer=(page>0?'< ':'  ')+(page+1)+'/'+pages.length+(page<pages.length-1?' >':'  ');
      drawText(ctx,footer,Math.floor(400-footer.length*6),280);
    }
    // A compact microphone marks capture; the last response and page stay visible.
    if(state.status==='listening') {ctx.fillStyle='#00ff00';ctx.fillRect(190,275,4,10);ctx.fillRect(186,282,2,5);ctx.fillRect(196,282,2,5);ctx.fillRect(188,287,8,2);ctx.fillRect(191,289,2,4);}
    return {page,pages:pages.length};
  }
  class HermesHUD {
    constructor(canvas,options={}) {this.canvas=canvas;canvas.width=600;canvas.height=300;this.ctx=canvas.getContext('2d',{alpha:false});this.pack=options.pack||null;this.atlas=options.atlas||null;this.state=initialState();this.scenario=options.scenario;}
    /** Loads an avatar pack: `packUrl` points at its manifest.json (default: the bundled placeholder). */
    async load(options={}) {
      let packUrl=new URL(options.packUrl||DEFAULT_PACK,document.baseURI);
      if(!/\.json$/i.test(packUrl.pathname))packUrl=new URL('manifest.json',packUrl.href.endsWith('/')?packUrl:packUrl.href+'/');
      this.pack=options.pack || await fetch(packUrl).then(r=>{if(!r.ok)throw Error('Avatar pack unavailable');return r.json();});
      if(this.pack.schema!==1)throw Error('Avatar pack schema 1 required');
      this.atlas=options.atlas || await new Promise((res,rej)=>{const im=new Image();im.onload=()=>res(im);im.onerror=()=>rej(Error('Avatar atlas unavailable'));im.src=new URL(this.pack.atlas.file,packUrl);});
      return this;
    }
    /** Seconds one cycle of the state's clip lasts (after fallbacks). */
    clipSeconds(state) { return this.pack ? clipSeconds(this.pack.states[resolveState(this.pack,state)]) : 0; }
    dispatch(event){this.state=reducer(this.state,event);return this.state;}
    render(state=this.state,time=0){return renderHUD(this.ctx,state,this.pack,this.atlas,time);}
    renderAt(time,scenario=this.scenario) {
      const beats=scenario.beats || scenario;
      const beat=beats.find(b=>time>=b.start && time<b.start+b.duration) || (time<beats[0].start?beats[0]:beats[beats.length-1]);
      const state={...initialState(beat.output ?? beat.response),...beat,output:beat.output ?? beat.response ?? '',avatarSince:beat.avatarSince ?? beat.animationSince ?? beat.start,page:beat.page ?? beat.pageIndex ?? 0};
      this.render(state,time);return state;
    }
  }
  Object.assign(HermesHUD,{FONT,GEOMETRY,LEGACY_IDS,STATUS_STATES,FALLBACKS,DEFAULT_PACK,drawText,paginate,initialState,reducer,avatarState,resolveState,animationFrame,renderHUD});
  root.HermesHUD=HermesHUD;
  if(typeof module!=='undefined')module.exports=HermesHUD;
})(typeof window!=='undefined'?window:globalThis);
