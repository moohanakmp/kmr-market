// KMR v28 reference engine: extracted from the user-provided index.html.
// Trading formulas below are unchanged. Wrapper handles freshness and independent public data.
const SIG_TUNE={
  XAUUSD:{atrMin:0.05, atrMax:0.60, rsiBuyLo:45, rsiBuyHi:62, rsiSellLo:38, rsiSellHi:55, bodyMin:0.40, srPct:0.006, rsiHardHi:78, rsiHardLo:22},
  XAGUSD:{atrMin:0.08, atrMax:1.10, rsiBuyLo:42, rsiBuyHi:64, rsiSellLo:36, rsiSellHi:58, bodyMin:0.35, srPct:0.009, rsiHardHi:80, rsiHardLo:20},
  BTCUSD:{atrMin:0.08, atrMax:1.40, rsiBuyLo:45, rsiBuyHi:62, rsiSellLo:38, rsiSellHi:55, bodyMin:0.38, srPct:0.008, rsiHardHi:82, rsiHardLo:18},
};

const ASSETS={XAUUSD:{dec:2,contractSz:100},XAGUSD:{dec:3,contractSz:100},BTCUSD:{dec:0,contractSz:1}};
const SPREAD={XAUUSD:0.35,XAGUSD:0.03,BTCUSD:5};
let engineSeries=[];
function getCandles(){return engineSeries;}
const ohlcData={};
function fmtP(sym,n){return n.toFixed(ASSETS[sym].dec);}
function ema(C,p){if(C.length<p)return null;const k=2/(p+1);let v=C.slice(0,p).reduce((a,b)=>a+b,0)/p;for(let i=p;i<C.length;i++)v=C[i]*k+v*(1-k);return v;}

function rsi(C,p){
  if(C.length<p+1)return null;
  let g=0,l=0;
  for(let i=C.length-p;i<C.length;i++){const d=C[i]-C[i-1];d>0?g+=d:l-=d;}
  const ag=g/p,al=l/p;if(al===0)return 100;return 100-(100/(1+(ag/al)));
}

function atr(candles,n=14){
  const N=Math.min(n,candles.length-1);if(N<2)return null;
  let s=0;for(let i=candles.length-N;i<candles.length;i++){const c=candles[i],p=candles[i-1];s+=Math.max(c.h-c.l,Math.abs(c.h-p.c),Math.abs(c.l-p.c));}
  return s/N;
}

function swingLevels(candles){
  const n=3,H=[],L=[];
  for(let i=n;i<candles.length-n;i++){
    const win=candles.slice(i-n,i+n+1);
    if(candles[i].h>=Math.max(...win.map(c=>c.h)))H.push(candles[i].h);
    if(candles[i].l<=Math.min(...win.map(c=>c.l)))L.push(candles[i].l);
  }
  return{H:H.slice(-6),L:L.slice(-6)};
}

function emaSeries(C,p){const out=new Array(C.length).fill(null);if(C.length<p)return out;let v=C.slice(0,p).reduce((a,b)=>a+b,0)/p;out[p-1]=v;const k=2/(p+1);for(let i=p;i<C.length;i++){v=C[i]*k+v*(1-k);out[i]=v;}return out;}

function macd(C,fast=12,slow=26,signalP=9){
  if(C.length<slow+signalP)return null;
  const eF=emaSeries(C,fast),eS=emaSeries(C,slow);
  const line=[];
  for(let i=0;i<C.length;i++){if(eF[i]!=null&&eS[i]!=null)line.push(eF[i]-eS[i]);}
  if(line.length<signalP+1)return null;
  const sig=emaSeries(line,signalP);
  const v=line[line.length-1],s=sig[sig.length-1];
  if(v==null||s==null)return null;
  const pv=line[line.length-2],ps=sig[sig.length-2];
  return{
    macd:v,signal:s,hist:v-s,prevHist:(pv!=null&&ps!=null)?pv-ps:null,
    bullCross:pv!=null&&ps!=null&&pv<=ps&&v>s,
    bearCross:pv!=null&&ps!=null&&pv>=ps&&v<s,
  };
}

function kmrAdx(c,n=14){
  if(!c||c.length<n*2+2)return null;
  let tr=0,pdm=0,mdm=0;const dxs=[];
  for(let i=1;i<c.length;i++){
    const up=c[i].h-c[i-1].h,dn=c[i-1].l-c[i].l;
    const p=(up>dn&&up>0)?up:0,m=(dn>up&&dn>0)?dn:0;
    const t=Math.max(c[i].h-c[i].l,Math.abs(c[i].h-c[i-1].c),Math.abs(c[i].l-c[i-1].c));
    if(i<=n){tr+=t;pdm+=p;mdm+=m;if(i<n)continue;}
    else{tr=tr-tr/n+t;pdm=pdm-pdm/n+p;mdm=mdm-mdm/n+m;}
    const pdi=tr?100*pdm/tr:0,mdi=tr?100*mdm/tr:0;
    dxs.push({dx:(pdi+mdi)?100*Math.abs(pdi-mdi)/(pdi+mdi):0,pdi,mdi});
  }
  if(dxs.length<n)return null;
  let a=dxs.slice(0,n).reduce((s,d)=>s+d.dx,0)/n;
  for(let i=n;i<dxs.length;i++)a=(a*(n-1)+dxs[i].dx)/n;
  const last=dxs[dxs.length-1];
  return{adx:a,pdi:last.pdi,mdi:last.mdi};
}

function kmrPivots(c,n=3){
  const H=[],L=[];
  for(let i=n;i<c.length-n;i++){
    let isH=true,isL=true;
    for(let j=i-n;j<=i+n;j++){if(j===i)continue;if(c[j].h>c[i].h)isH=false;if(c[j].l<c[i].l)isL=false;}
    if(isH)H.push({i,p:c[i].h});if(isL)L.push({i,p:c[i].l});
  }
  return{H,L};
}

function kmrStructure(c){
  const pv=kmrPivots(c.slice(-90),3);
  const H=pv.H.slice(-2),L=pv.L.slice(-2);
  if(H.length<2||L.length<2)return{state:'UNCLEAR',H:pv.H,L:pv.L};
  const hh=H[1].p>H[0].p,hl=L[1].p>L[0].p,lh=H[1].p<H[0].p,ll=L[1].p<L[0].p;
  return{state:hh&&hl?'BULL':lh&&ll?'BEAR':'MIXED',H:pv.H,L:pv.L,lastH:H[1].p,lastL:L[1].p};
}

function kmrVolumePressure(c,n=20){
  const w=c.slice(-n).filter(k=>(k.v||0)>0&&k.h>k.l);
  if(w.length<Math.min(8,n))return{ok:false};
  let buy=0,sell=0;const cvd=[];let run=0;
  w.forEach(k=>{const b=k.v*(k.c-k.l)/(k.h-k.l);buy+=b;sell+=k.v-b;run+=b-(k.v-b);cvd.push(run);});
  const tot=buy+sell,buyPct=tot?buy/tot*100:50;
  const half=Math.floor(cvd.length/2);
  const cvdSlope=cvd[cvd.length-1]-cvd[half];
  let obv=0;const obvS=[];const all=c.slice(-n*2).filter(k=>(k.v||0)>0);
  for(let i=1;i<all.length;i++){obv+=all[i].c>all[i-1].c?all[i].v:all[i].c<all[i-1].c?-all[i].v:0;obvS.push(obv);}
  const obvUp=obvS.length>6?obvS[obvS.length-1]>obvS[Math.floor(obvS.length/2)]:null;
  const vols=w.map(k=>k.v),avg=vols.slice(0,-1).reduce((a,b)=>a+b,0)/Math.max(1,vols.length-1);
  const relVol=avg?vols[vols.length-1]/avg:1;
  const priceUp=w[w.length-1].c>w[half].c;
  const divergence=priceUp&&cvdSlope<0?'BEAR_DIV':!priceUp&&cvdSlope>0?'BULL_DIV':null;
  const side=buyPct>=56?'BUY':buyPct<=44?'SELL':'BALANCED';
  return{ok:true,buyPct,sellPct:100-buyPct,cvdSlope,obvUp,relVol,divergence,side,samples:w.length};
}

function kmrSweep(c,dir,pivots){
  const n=c.length,recent=c.slice(-4);
  if(dir==='BUY'){
    const lvls=pivots.L.filter(p=>p.i<n-4-1).slice(-3).map(p=>p.p);
    return recent.some(k=>lvls.some(l=>k.l<l&&k.c>l));
  }
  const lvls=pivots.H.filter(p=>p.i<n-4-1).slice(-3).map(p=>p.p);
  return recent.some(k=>lvls.some(h=>k.h>h&&k.c<h));
}

function kmrTfBias(c){
  if(!c||c.length<22)return null;
  const C=c.map(k=>k.c),p=C[C.length-1];
  if(C.length>=55){const a=ema(C,20),b=ema(C,50);if(a>b&&p>b)return'BULL';if(a<b&&p<b)return'BEAR';return'NEUTRAL';}
  const a=ema(C,9),b=ema(C,21);return a>b?'BULL':a<b?'BEAR':'NEUTRAL';
}

function kmrSmartScan(candles15,h1,h4,ctx){
  const out={gates:[],factors:[],score:0,dir:null,tier:null};
  const c=candles15;
  if(!c||c.length<60){out.reason='Collecting candle history';out.wait='Needs ~60 closed 15m candles';return out;}
  const tune=ctx.tune;
  const C=c.map(k=>k.c),last=c[c.length-1],price=last.c;
  const e20=ema(C,20),e50=ema(C,50),e200=C.length>=200?ema(C,200):null;
  const atrV=atr(c)||price*0.001,atrPct=atrV/price*100;
  const r=rsi(C,14),m=macd(C),dmi=kmrAdx(c,14);
  const st=kmrStructure(c);
  const vp=kmrVolumePressure(c,20);
  const b1=kmrTfBias(h1),b4=kmrTfBias(h4);
  Object.assign(out,{price,atrV,atrPct,e20,e50,e200,rsi:r,macd:m,adx:dmi,structure:st,vp,bias1h:b1,bias4h:b4});

  // ── DIRECTION: decided by the higher timeframes, never by the 15m alone ──
  let dir=null;
  if(b1==='BULL'&&b4==='BULL')dir='BUY';
  else if(b1==='BEAR'&&b4==='BEAR')dir='SELL';
  out.dir=dir;
  const want=dir==='BUY'?'BULL':'BEAR',sgn=dir==='BUY'?1:-1;
  const gate=(key,ok,label,wait)=>{out.gates.push({key,ok,label,wait});return ok;};

  // ── HARD GATES — any failure means NO TRADE, with a clear "what to wait for" ──
  gate('htf',!!dir,dir?`Higher timeframes agree: ${dir==='BUY'?'bullish':'bearish'}`:`1H ${b1||'—'} vs 4H ${b4||'—'} — no clear higher-timeframe direction`,'Wait for 1H and 4H to point the same way');
  const volOk=atrPct>=tune.atrMin&&atrPct<=tune.atrMax;
  gate('vol',volOk,`Volatility ${atrPct.toFixed(2)}% ${volOk?'in':'outside'} this asset's normal ${tune.atrMin}–${tune.atrMax}% band`,atrPct>tune.atrMax?'Wait for the volatility spike to calm down':'Market too quiet — wait for participation');
  const trending=!dmi||dmi.adx>=20;
  gate('adx',trending,dmi?`ADX ${dmi.adx.toFixed(0)} — ${dmi.adx>=25?'strong trend':dmi.adx>=20?'trend present':'ranging/choppy market'}`:'ADX not ready',`Ranging market — trend signals whipsaw here. Wait for ADX > 20`);
  const extension=dir&&e20?sgn*(price-e20)/atrV:0;
  const notChasing=extension<=1.6;
  gate('ext',notChasing,dir?`Price ${extension.toFixed(1)}× ATR ${dir==='BUY'?'above':'below'} EMA20 — ${notChasing?'not overextended':'overextended, chasing'}`:'Extension check',dir&&e20?`Wait for a pullback toward EMA20 ≈ ${e20.toFixed(ctx.dec)}`:'');
  // Chasing = a big BODY moving in the trade direction. A long WICK (hammer / shooting
  // star) is a rejection, which is exactly the entry a trader wants, so wicks don't count.
  const dirBody=dir?sgn*(last.c-last.o):0;
  const bigBar=dirBody>atrV*1.5;
  gate('spike',!bigBar,bigBar?`Last candle body ${(dirBody/atrV).toFixed(1)}× ATR in trade direction — spike, entry would be late`:'Trigger candle not a spike','Let the spike candle settle — enter on the retest, not the spike');
  const rsiOk=r==null||(dir==='BUY'?r<tune.rsiHardHi:r>tune.rsiHardLo);
  gate('rsi',rsiOk,`RSI ${r!=null?r.toFixed(0):'—'} ${rsiOk?'not at an extreme':'at an exhaustion extreme'}`,'Exhaustion zone — wait for RSI to cool back toward 50');
  const newsHit=(ctx.events||[]).find(ev=>ev.ts>=ctx.now-15*60000&&ev.ts<=ctx.now+30*60000);
  gate('news',!newsHit,newsHit?`High-impact news: ${newsHit.title} ${ev2txt(newsHit,ctx.now)}`:'No high-impact USD news in the next 30 min',newsHit?'Stand aside until 15 min after the release — spreads and spikes are unpredictable':'');
  const rollover=ctx.isMetal&&ctx.istHour>=2&&ctx.istHour<4;
  gate('roll',!rollover,rollover?'Daily rollover hours (02:00–04:00 IST) — spreads widen':'Outside rollover hours','Wait until after 04:00 IST');
  const volAgainst=vp.ok&&dir&&((dir==='BUY'&&vp.buyPct<40)||(dir==='SELL'&&vp.buyPct>60));
  gate('volm',!volAgainst,volAgainst?`Volume says the opposite: ${vp.buyPct.toFixed(0)}% buying vs ${vp.sellPct.toFixed(0)}% selling`:'Volume not fighting the trade','Wait for volume to turn — the crowd is on the other side');

  // ── LOCATION + TRIGGER: a trader only enters at value, on a real trigger ──
  // Value = the pullback reached EMA20/EMA50 (within 0.6 ATR, measured on the bar's
  // extreme, since a pullback that tagged the EMA and bounced still counts), an aligned
  // order block, or a liquidity sweep just happened. No value zone = no trade.
  let valueZone=null,swept=false,ob=null,rejection=false,closeWith=false;
  if(dir){
    const recent=c.slice(-3);
    // A bar "tagged" a level if any part of its range came within 0.6 ATR of it — this
    // includes bars that pierced straight through and got rejected, which is a stronger
    // test of the level, not a weaker one. Close must still be back on our side of EMA50.
    const touched=lvl=>lvl!=null&&recent.some(k=>k.l<=lvl+atrV*0.6&&k.h>=lvl-atrV*0.6)&&(e50==null||sgn*(price-e50)>-atrV*0.5);
    ob=(ctx.obs||[]).find(o=>(dir==='BUY'?o.type==='BULL':o.type==='BEAR')&&price>=o.bottom-atrV*0.3&&price<=o.top+atrV*0.3)||null;
    swept=kmrSweep(c,dir,st);
    valueZone=touched(e20)?'EMA20':touched(e50)?'EMA50':ob?'order block':swept?'liquidity sweep':null;
    const body=Math.abs(last.c-last.o),rng=Math.max(last.h-last.l,1e-9);
    const lowerWick=Math.min(last.o,last.c)-last.l,upperWick=last.h-Math.max(last.o,last.c);
    rejection=dir==='BUY'?lowerWick>=body*1.5&&lowerWick>=rng*0.4:upperWick>=body*1.5&&upperWick>=rng*0.4;
    closeWith=sgn*(last.c-last.o)>0&&body/rng>=tune.bodyMin;
  }
  const lostE50=dir&&e50!=null&&sgn*(price-e50)<=-atrV*0.5;
  gate('value',!!valueZone,valueZone?`At value: ${valueZone}`:lostE50?`Closed ${dir==='BUY'?'below':'above'} EMA50 — the pullback broke the trend line`:'Price is not at a value zone (EMA20/50, order block or sweep)',lostE50?`Wait for a close back ${dir==='BUY'?'above':'below'} EMA50 ≈ ${e50.toFixed(ctx.dec)} before entering`:dir&&e20?`Wait for a pullback to EMA20 ≈ ${e20.toFixed(ctx.dec)}${e50?` or EMA50 ≈ ${e50.toFixed(ctx.dec)}`:''}`:'Wait for a pullback');
  gate('trigger',rejection||closeWith,rejection?'Trigger: rejection wick':closeWith?'Trigger: candle closed in trade direction':'No trigger candle yet','Wait for a rejection wick or a candle that closes in the trade direction');
  gate('struct',st.state!==(dir==='BUY'?'BEAR':'BULL'),`15m structure ${st.state}`,'15m structure is against the higher timeframe — wait for it to turn');

  // ── SCORED FACTORS (0–100): how GOOD the setup is, once it's allowed at all ──
  const f=(pts,max,label)=>{out.factors.push({pts,max,label,pass:pts>=max*0.6});return pts;};
  let s=0;
  if(dir){
    s+=f(b4===want?20:b4==='NEUTRAL'?8:0,20,`4H trend ${b4||'—'}`);
    s+=f(b1===want?15:0,15,`1H trend ${b1||'—'}`);
    s+=f(st.state===want?15:st.state==='MIXED'?5:0,15,`15m structure ${st.state==='BULL'?'higher highs & lows':st.state==='BEAR'?'lower highs & lows':st.state.toLowerCase()}`);
    s+=f(valueZone?15:0,15,valueZone?`Pullback into value (${valueZone})`:'Not at a value zone');
    s+=f(rejection?10:closeWith?8:0,10,rejection?'Rejection wick trigger':closeWith?'Candle closed in trade direction':'No trigger candle yet');
    // Momentum: MACD histogram on our side and not fading, RSI on our side of 50
    const histOk=m&&sgn*m.hist>0,histRising=m&&m.prevHist!=null&&sgn*(m.hist-m.prevHist)>0;
    const rsiSide=r!=null&&sgn*(r-50)>0;
    s+=f((histOk?5:0)+(histRising?2:0)+(rsiSide?3:0),10,`Momentum: MACD ${histOk?'with':'against'}, RSI ${r!=null?r.toFixed(0):'—'}`);
    // Volume pressure
    let vpts=0;
    if(vp.ok){
      const withPct=dir==='BUY'?vp.buyPct:vp.sellPct;
      vpts=(withPct>=58?7:withPct>=52?4:0)+((dir==='BUY'?vp.obvUp:vp.obvUp===false)?3:0);
      if((dir==='BUY'&&vp.divergence==='BEAR_DIV')||(dir==='SELL'&&vp.divergence==='BULL_DIV'))vpts=Math.max(0,vpts-4);
    }
    s+=f(vpts,10,vp.ok?`Volume: ${vp.buyPct.toFixed(0)}% buy / ${vp.sellPct.toFixed(0)}% sell${vp.divergence?' · divergence':''}`:'Volume: no data from this feed');
    s+=f(swept?5:0,5,swept?'Liquidity sweep: stops taken then reclaimed':'No liquidity sweep');
    s+=dmi&&dmi.adx>=25&&(dir==='BUY'?dmi.pdi>dmi.mdi:dmi.mdi>dmi.pdi)?5:0;
  }
  out.valueZone=valueZone;out.swept=swept;out.ob=ob;
  out.score=Math.max(0,Math.min(100,Math.round(s)));
  const allGates=out.gates.every(g=>g.ok);
  out.gatesOk=allGates;
  out.tier=allGates&&out.score>=84?'HIGH':allGates&&out.score>=70?'MED':null;
  const firstFail=out.gates.find(g=>!g.ok);
  out.reason=firstFail?firstFail.label:out.tier?`${out.tier} setup — ${out.score}/100`:`Setup quality ${out.score}/100 — needs 70+`;
  out.wait=firstFail?firstFail.wait:out.tier?'':(out.factors.filter(x=>!x.pass).map(x=>x.label).slice(0,2).join(' · ')||'Wait for more confluence');
  return out;
}

function ev2txt(ev,now){const m=Math.round((ev.ts-now)/60000);return m>=0?`in ${m} min`:`${-m} min ago`;}

function kmrTradePlan(scan,dec){
  if(!scan.dir)return null;
  const {dir,price,atrV,e20,structure}=scan,sgn=dir==='BUY'?1:-1;
  const stretched=e20&&sgn*(price-e20)>atrV*0.8;
  const entry=stretched?e20+sgn*atrV*0.2:price;
  const entryType=stretched?'LIMIT':'MARKET';
  const swings=dir==='BUY'?structure.L.map(p=>p.p).filter(p=>p<entry):structure.H.map(p=>p.p).filter(p=>p>entry);
  const swing=swings.length?(dir==='BUY'?Math.max(...swings.slice(-3)):Math.min(...swings.slice(-3))):null;
  let sl=swing!=null?swing-sgn*atrV*0.3:entry-sgn*atrV*1.5;
  let risk=sgn*(entry-sl);
  if(risk<atrV*1.0){sl=entry-sgn*atrV*1.0;risk=atrV*1.0;}
  if(risk>atrV*2.5){sl=entry-sgn*atrV*2.5;risk=atrV*2.5;}
  const opp=dir==='BUY'?structure.H.map(p=>p.p).filter(p=>p>entry+risk*1.3):structure.L.map(p=>p.p).filter(p=>p<entry-risk*1.3);
  const liq=opp.length?(dir==='BUY'?Math.min(...opp):Math.max(...opp)):null;
  const tp1=entry+sgn*risk*1.0;
  const tp2=liq!=null&&Math.abs(liq-entry)<=risk*3.2?liq-sgn*atrV*0.1:entry+sgn*risk*2.0;
  const tp3=entry+sgn*Math.max(risk*3.0,Math.abs(tp2-entry)+risk);
  return{dir,entry,entryType,sl,tp1,tp2,tp3,risk,rr:Math.abs(tp2-entry)/risk,
    slWhy:swing!=null?`${dir==='BUY'?'Below':'Above'} last swing ${dir==='BUY'?'low':'high'} + 0.3 ATR buffer`:'1.5× ATR (no clean swing nearby)',
    tp2Why:liq!=null&&Math.abs(liq-entry)<=risk*3.2?`Next ${dir==='BUY'?'swing high':'swing low'} liquidity`:'2R projection',
    entryWhy:stretched?`Price is stretched ${(sgn*(price-e20)/atrV).toFixed(1)}× ATR from EMA20 — a limit at the pullback gives a far better risk`:'Price is at value — market entry is fine',
    invalid:sl};
}

function bbAt(C,i,n=20,k=2){if(i<n-1)return null;const w=C.slice(i-n+1,i+1);const m=w.reduce((a,b)=>a+b,0)/n;const sd=Math.sqrt(w.reduce((a,b)=>a+(b-m)**2,0)/n);return{m,u:m+k*sd,l:m-k*sd};}

function scalpScan(sym,c5,c15,h1,ctx){
  const out={sym,gates:[],dir:null,setup:null,why:[],look:''};
  const g=(ok,label)=>{out.gates.push({ok,label});return ok;};
  if(!c5||c5.length<60||!c15||c15.length<55){out.look='Loading 5m / 15m candles…';g(false,'Candle history still loading');return out;}
  const C=c5.map(k=>k.c),n=C.length-1,last=c5[n],prev=c5[n-1];
  const A=atr(c5)||last.c*0.001;
  const e9=emaSeries(C,9),e21=emaSeries(C,21);
  const r7=[rsi(C.slice(0,n-2),7),rsi(C.slice(0,n-1),7),rsi(C.slice(0,n),7),rsi(C,7)];
  const C15=c15.map(k=>k.c),t20=ema(C15,20),t50=ema(C15,50),p15=C15[C15.length-1];
  const dmi=kmrAdx(c15,14),adx=dmi?dmi.adx:null;
  const trend=t20!=null&&t50!=null?(t20>t50&&p15>t50?'BULL':t20<t50&&p15<t50?'BEAR':'FLAT'):'FLAT';
  const b1=h1&&h1.length>60?kmrTfBias(h1):null;
  const bb=bbAt(C,n),bbP=bbAt(C,n-1);
  const vols=c5.slice(-21,-1).map(k=>k.v||0),vAvg=vols.reduce((a,b)=>a+b,0)/Math.max(1,vols.length);
  const relVol=vAvg?(last.v||0)/vAvg:1;
  const spread=ctx.spread,dec=ctx.dec;
  Object.assign(out,{A,trend,adx,rsi7:r7[3],e9:e9[n],e21:e21[n],bb,b1,price:last.c,relVol,barT:last.t});
  // ── safety gates ──
  g(!ctx.isMetal||ctx.marketOpen,ctx.isMetal&&!ctx.marketOpen?'Metals market closed':'Market open');
  const ist=kmrIstHour(ctx.now);
  g(!(ctx.isMetal&&ist>=2&&ist<4),ctx.isMetal&&ist>=2&&ist<4?'Rollover 02:00–04:00 IST — spreads widen':'Outside rollover hours');
  const ev=(ctx.events||[]).find(e=>e.ts>=ctx.now-15*60e3&&e.ts<=ctx.now+10*60e3);
  g(!ev,ev?`High-impact news: ${ev.title} ${ev2txt(ev,ctx.now)} — no scalps ±10/15 min`:'No high-impact news in the next 10 min');
  g(A>=2.5*spread,A>=2.5*spread?`5m range ${fmtP(sym,A)} vs spread ${fmtP(sym,spread)} — enough room`:`5m range ${fmtP(sym,A)} too small vs spread ${fmtP(sym,spread)} — dead market`);
  g(last.h-last.l<=3*A,last.h-last.l>3*A?'Last 5m candle is a spike (>3× ATR) — let it settle':'No spike candle');
  g(ctx.now-last.t<=12*60e3,ctx.now-last.t>12*60e3?'5m data is stale — waiting for a fresh candle':'5m data fresh');
  // ── setups ──
  const body=Math.abs(last.c-last.o),rng=Math.max(last.h-last.l,1e-9);
  const lw=Math.min(last.o,last.c)-last.l,uw=last.h-Math.max(last.o,last.c);
  const minR=Math.min(r7[0],r7[1],r7[2]),maxR=Math.max(r7[0],r7[1],r7[2]);
  // A — trend pullback
  if(trend==='BULL'&&e9[n]>e21[n]){
    const tagged=[n-2,n-1,n].some(i=>c5[i].l<=e21[i]+0.15*A);
    const trig=(last.c>last.o&&last.c>e9[n]&&body/rng>=0.4)||(lw>=rng*0.5&&last.c>e21[n]);
    const rsiOk=minR<=50&&r7[3]>r7[2];
    const ext=(last.c-e21[n])/A;
    if(tagged&&trig&&rsiOk&&ext<=1.0){out.dir='BUY';out.setup='Trend pullback';out.why=['15m uptrend (EMA20 > EMA50)',`5m dipped to EMA21 ≈ ${fmtP(sym,e21[n])}`,last.c>last.o?'Bullish trigger candle closed above EMA9':'Hammer rejection off EMA21',`RSI7 turning up (${r7[2].toFixed(0)} → ${r7[3].toFixed(0)})`];}
    else out.look=tagged?'Uptrend + dip at EMA21 — waiting for a bullish trigger candle':`Uptrend — waiting for a dip to EMA21 ≈ ${fmtP(sym,e21[n])}`;
  }else if(trend==='BEAR'&&e9[n]<e21[n]){
    const tagged=[n-2,n-1,n].some(i=>c5[i].h>=e21[i]-0.15*A);
    const trig=(last.c<last.o&&last.c<e9[n]&&body/rng>=0.4)||(uw>=rng*0.5&&last.c<e21[n]);
    const rsiOk=maxR>=50&&r7[3]<r7[2];
    const ext=(e21[n]-last.c)/A;
    if(tagged&&trig&&rsiOk&&ext<=1.0){out.dir='SELL';out.setup='Trend pullback';out.why=['15m downtrend (EMA20 < EMA50)',`5m rallied to EMA21 ≈ ${fmtP(sym,e21[n])}`,last.c<last.o?'Bearish trigger candle closed below EMA9':'Shooting-star rejection off EMA21',`RSI7 turning down (${r7[2].toFixed(0)} → ${r7[3].toFixed(0)})`];}
    else out.look=tagged?'Downtrend + rally at EMA21 — waiting for a bearish trigger candle':`Downtrend — waiting for a rally to EMA21 ≈ ${fmtP(sym,e21[n])}`;
  }
  // B — momentum breakout
  if(!out.dir&&trend!=='FLAT'&&adx!=null&&adx>=20){
    const hh=Math.max(...c5.slice(n-12,n).map(k=>k.h)),ll=Math.min(...c5.slice(n-12,n).map(k=>k.l));
    if(trend==='BULL'&&last.c>hh&&relVol>=1.4&&last.c>last.o&&body/rng>=0.55&&(last.c-e9[n])/A<=1.2&&r7[3]<80){out.dir='BUY';out.setup='Momentum breakout';out.why=[`15m uptrend, ADX ${adx.toFixed(0)}`,`Closed above the 1-hour high ${fmtP(sym,hh)}`,`Volume ${relVol.toFixed(1)}× average`];}
    if(trend==='BEAR'&&last.c<ll&&relVol>=1.4&&last.c<last.o&&body/rng>=0.55&&(e9[n]-last.c)/A<=1.2&&r7[3]>20){out.dir='SELL';out.setup='Momentum breakout';out.why=[`15m downtrend, ADX ${adx.toFixed(0)}`,`Closed below the 1-hour low ${fmtP(sym,ll)}`,`Volume ${relVol.toFixed(1)}× average`];}
  }
  // C — range fade
  if(!out.dir&&bb&&bbP&&(trend==='FLAT'||(adx!=null&&adx<20))){
    if((last.l<=bb.l||prev.l<=bbP.l)&&last.c>bb.l&&last.c>last.o&&lw>=rng*0.35&&Math.min(r7[1],r7[2],r7[3])<=30&&trend!=='BEAR'&&bb.m-last.c>=0.45*A){
      out.dir='BUY';out.setup='Range fade';out.tpCap=bb.m-last.c;out.why=[`Ranging (ADX ${adx!=null?adx.toFixed(0):'—'})`,`Tagged the lower Bollinger band ${fmtP(sym,bb.l)} and closed back inside`,`Target: middle band ${fmtP(sym,bb.m)}`];}
    else if((last.h>=bb.u||prev.h>=bbP.u)&&last.c<bb.u&&last.c<last.o&&uw>=rng*0.35&&Math.max(r7[1],r7[2],r7[3])>=70&&trend!=='BULL'&&last.c-bb.m>=0.45*A){
      out.dir='SELL';out.setup='Range fade';out.tpCap=last.c-bb.m;out.why=[`Ranging (ADX ${adx!=null?adx.toFixed(0):'—'})`,`Tagged the upper Bollinger band ${fmtP(sym,bb.u)} and closed back inside`,`Target: middle band ${fmtP(sym,bb.m)}`];}
    else if(!out.look)out.look=`Range — waiting for a tag of ${fmtP(sym,bb.l)} (buy) or ${fmtP(sym,bb.u)} (sell)`;
  }
  if(!out.look)out.look=trend==='FLAT'?'No trend and no band tag yet':'Trend present — waiting for the pullback';
  if(out.dir&&b1)out.why.push(b1===(out.dir==='BUY'?'BULL':'BEAR')?`1H agrees (${b1})`:`1H is ${b1} — smaller edge`);
  out.gatesOk=out.gates.every(x=>x.ok);
  return out;
}

function scalpLevels(sym,dir,entry,scan,c5,cfg){
  const A=scan.A,spread=SPREAD[sym]||0,cs=ASSETS[sym].contractSz,sgn=dir==='BUY'?1:-1;
  let tpDist=Math.max(0.8*A,2.5*spread);
  if(scan.tpCap)tpDist=Math.min(tpDist,Math.max(scan.tpCap,0.45*A));
  const last3=c5.slice(-3);
  const ext=dir==='BUY'?Math.min(...last3.map(k=>k.l)):Math.max(...last3.map(k=>k.h));
  let slDist=Math.min(Math.max(Math.abs(entry-ext)+0.15*A,1.0*A),1.6*A);
  const net=tpDist-spread;if(net<=0)return{reject:'Spread eats the whole target'};
  let lot=Math.max(0.01,Math.round(cfg.target/(net*cs)/0.01)*0.01);
  let slUsd=(slDist+spread)*lot*cs;
  while(slUsd>cfg.maxLoss&&lot>0.0101){lot=+(lot-0.01).toFixed(2);slUsd=(slDist+spread)*lot*cs;}
  if(slUsd>cfg.maxLoss*1.15)return{reject:`Even 0.01 lot would risk $${slUsd.toFixed(2)} here — above your $${cfg.maxLoss} max loss`};
  const units=lot*cs;
  return{entry,tp:entry+sgn*tpDist,sl:entry-sgn*slDist,beAt:entry+sgn*tpDist*0.6,lot:+lot.toFixed(2),units,tpUsd:net*units,slUsd,tpDist,slDist};
}

function orderBlocks(sym,tf){
  const all=tf&&tf!=='15m'?(ohlcData[sym]?.[tf]||[]):getCandles(sym);
  if(!all||all.length<30)return[];
  const c=all.slice(0,-1).slice(-220); // closed candles only
  const price=all[all.length-1].c;
  const A=atr(c)||price*0.002;
  const obs=[];
  for(let i=10;i<c.length-1;i++){
    for(const dir of ['BULL','BEAR']){
      const ob=c[i];
      const opp=dir==='BULL'?ob.c<ob.o:ob.c>ob.o;if(!opp)continue;
      let j=i+1,moved=0,endIdx=-1;
      for(;j<=Math.min(i+3,c.length-1);j++){
        moved=dir==='BULL'?c[j].c-ob.c:ob.c-c[j].c;
        if(moved>=1.5*A){endIdx=j;break;}
      }
      if(endIdx<0)continue;
      const prior=c.slice(i-10,i);
      const bos=dir==='BULL'?c[endIdx].c>Math.max(...prior.map(k=>k.h)):c[endIdx].c<Math.min(...prior.map(k=>k.l));
      if(!bos)continue;
      const top=ob.h,bottom=ob.l;
      let mitigated=false,tested=0,inside=false;
      for(let k=endIdx+1;k<c.length;k++){
        if(dir==='BULL'?c[k].c<bottom:c[k].c>top){mitigated=true;break;}
        const touch=dir==='BULL'?c[k].l<=top:c[k].h>=bottom;
        if(touch&&!inside)tested++; // count distinct revisits, not every candle inside the zone
        inside=touch;
      }
      if(mitigated)continue;
      obs.push({type:dir,t:ob.t,top,bottom,strength:+(moved/A).toFixed(1),fresh:tested===0,tested,dist:Math.abs((top+bottom)/2-price)/A});
    }
  }
  // collapse overlapping zones of the same type (keep the strongest)
  const out=[];
  obs.sort((a,b)=>b.strength-a.strength).forEach(o=>{if(!out.some(x=>x.type===o.type&&x.bottom<=o.top&&o.bottom<=x.top))out.push(o);});
  return out.sort((a,b)=>a.dist-b.dist).slice(0,4);
}

function kmrIstHour(t){const ms=t==null?Date.now():t;return((ms/3600000+5.5)%24+24)%24;}

// Integration adapter; the extracted v28 functions above remain unchanged.
function v28Analyze(text) {
 const x=JSON.parse(text),s=x.symbol,now=x.now;
 if(!ASSETS[s])throw Error('Unsupported asset');
 const closed=(arr,ms)=>(arr||[]).filter(b=>b.t+ms<=now);
 const c=closed(x.c15,900000),h1=closed(x.h1,3600000),h4=closed(x.h4,14400000),c5=closed(x.c5,300000);
 engineSeries=c.concat([{c:x.price}]);
 const ctx={tune:SIG_TUNE[s],obs:orderBlocks(s),events:x.events||[],now,istHour:kmrIstHour(now),isMetal:s!=='BTCUSD',marketOpen:x.marketOpen,dec:ASSETS[s].dec,spread:SPREAD[s]};
 const scan=kmrSmartScan(c,h1,h4,ctx);
 if(c.length<60)return JSON.stringify({ready:false,reason:scan.reason,scan});
 const fallback=scan.dir||(scan.bias1h==='BULL'?'BUY':scan.bias1h==='BEAR'?'SELL':scan.structure.state==='BULL'?'BUY':scan.structure.state==='BEAR'?'SELL':scan.e50!=null&&scan.price<scan.e50?'SELL':'BUY');
 const manual=kmrTradePlan(Object.assign({},scan,{dir:fallback,price:x.price}),ASSETS[s].dec);
 const blocks=[];
 if(!x.marketOpen&&s!=='BTCUSD')blocks.push('Metals market closed');
 if(!x.calendarAt||now-x.calendarAt>1200000)blocks.push('Public news calendar unavailable or stale');
 if(!x.quoteAt||now-x.quoteAt>90000||x.quoteAt>now+30000)blocks.push('Public reference price is stale or future-dated');
 for(const [bars,ms,label]of [[c,900000,'15m'],[h1,3600000,'1H'],[h4,14400000,'4H']])if(!bars.length||now-bars[bars.length-1].t-ms>2*ms)blocks.push(label+' closed history is stale');
 let plan=scan.tier?kmrTradePlan(Object.assign({},scan,{price:x.price}),ASSETS[s].dec):null;
 if(plan){const sign=scan.dir==='BUY'?1:-1,ran=sign*(x.price-scan.price);if(ran>plan.risk*.5||ran<-scan.atrV*.6){blocks.push('Live reference price moved beyond the v28 entry guard');plan=null;}else if(plan.entryType==='LIMIT'){blocks.push('Auto setup stretched: wait for the pullback');plan=null;}}
 if(blocks.length)plan=null;
 if(plan)plan.atrV=scan.atrV;
 const scalp=scalpScan(s,c5,c,h1,ctx);
 let scalpPlan=null;
 if(scalp.dir&&scalp.gatesOk&&blocks.filter(b=>!b.startsWith('Auto setup')).length===0){const sign=scalp.dir==='BUY'?1:-1,ran=sign*(x.price-scalp.price);if(ran<=.4*scalp.A&&ran>=-.5*scalp.A){const lv=scalpLevels(s,scalp.dir,x.price,scalp,c5,{target:1,maxLoss:4});if(!lv.reject)scalpPlan=lv;}}
 return JSON.stringify({ready:true,symbol:s,source:x.source,quoteAt:x.quoteAt,scan,manual,manualGrade:scan.tier&&!blocks.length?'CONFIRMED':'LOWER_CONFIDENCE',auto:plan,scalp,scalpPlan,blocks,sourceBar:c[c.length-1].t+900000,scalpBar:c5.length?c5[c5.length-1].t+300000:null,reason:blocks[0]||scan.reason,suggestedLot:scan.tier==='HIGH'?.02:.01,volumeLabel:'Candle close-location estimate, not aggressor order-flow delta'});
}
