import {useEffect,useState} from 'react';
type Signal={id:number;symbol:string;timeframe:string;direction:string;status:string;entry:number;stopLoss:number;tp1:number;tp2:number;quality:number;reason:string;createdAt:string;expiresAt:string;trackingGap:boolean};
type Scan={id:string;symbol:string;timeframe:string;lastBar:string;lastScanAt:string;lastQuoteAt:string;receivedAt:string;reason:string;quality:number};
type Scanner={timeframes:string[];scans:Scan[];serverTime:string};
export default function AutoSignals({auth}:{auth:string}){
 const [signals,setSignals]=useState<Signal[]>([]),[scanner,setScanner]=useState<Scanner>(),[error,setError]=useState(''),[tf,setTf]=useState('ALL'),[symbol,setSymbol]=useState('ALL'),[loadedAt,setLoadedAt]=useState(Date.now());
 useEffect(()=>{let active=true;const controller=new AbortController();let timer:ReturnType<typeof setTimeout>;
  async function refresh(){try{const headers={Authorization:'Basic '+auth};const [a,b]=await Promise.all(['/api/signals','/api/scanner'].map(url=>fetch(url,{headers,signal:controller.signal})));if(!a.ok||!b.ok)throw new Error('Cannot load scanner. Check your session or connection.');const [data,state]=await Promise.all([a.json(),b.json()]);if(active){setSignals(data);setScanner(state);setLoadedAt(Date.now());setError('');}}catch(e){if(active)setError(e instanceof Error?e.message:'Scanner unavailable');}finally{if(active)timer=setTimeout(refresh,15000);}}
  void refresh();return()=>{active=false;controller.abort();clearTimeout(timer);};
 },[auth]);
 const now=scanner?new Date(scanner.serverTime).getTime()+Date.now()-loadedAt:Date.now();
 const fresh=error?0:scanner?.scans.filter(s=>now-new Date(s.lastQuoteAt).getTime()<90000).length||0;
 const symbols=Array.from(new Set(scanner?.scans.map(s=>s.symbol)||signals.map(s=>s.symbol))).sort();
 const filtered=signals.filter(s=>(tf==='ALL'||tf===s.timeframe)&&(symbol==='ALL'||symbol===s.symbol));
 const scans=scanner?.scans.filter(s=>(tf==='ALL'||tf===s.timeframe)&&(symbol==='ALL'||symbol===s.symbol))||[];
 return <section className="autosignals"><div className="sectionhead"><h2>Auto Signals <span className="badge">MT5</span></h2><b className={fresh?'buy':'sell'}>{fresh?'Live feed':'Waiting for MT5 feed'}</b></div>
 <p>EMA breakout strategy · All 21 timeframes · Sampled quote tracking · No broker orders</p>
 <p>Quality is a rule score, not a win probability. Strategy has not been backtested or calibrated to your broker.</p>
 {error&&<p role="alert" className="error">{error}</p>}
 <div className="filters"><label>Instrument<select value={symbol} onChange={e=>setSymbol(e.target.value)}><option value="ALL">All instruments</option>{symbols.map(s=><option key={s}>{s}</option>)}</select></label><label>Timeframe<select value={tf} onChange={e=>setTf(e.target.value)}><option value="ALL">All timeframes</option>{scanner?.timeframes.map(t=><option key={t}>{t}</option>)}</select></label><div><small>FRESH STREAMS</small><h2>{fresh} / {scanner?.scans.length||0}</h2></div></div>
 {!scanner?.scans.length&&<div className="notice">Set MT5_FEED_PASSWORD in Render, then run bridge/mt5_bridge.py on your Windows PC with MT5 logged in. Signals appear when the strategy conditions are met.</div>}
 <div className="table"><table><thead><tr><th>Instrument</th><th>TF</th><th>Side</th><th>Status</th><th>Quality</th><th>Entry</th><th>SL</th><th>TP1</th><th>TP2</th><th>Created</th></tr></thead><tbody>{filtered.map(s=>{const expired=s.status==='PENDING'&&new Date(s.expiresAt).getTime()<=now;return <tr key={s.id} title={s.reason}><td>{s.symbol}</td><td>{s.timeframe}</td><td className={s.direction.toLowerCase()}>{s.direction}</td><td>{expired?'EXPIRED (feed pending)':s.status}{s.trackingGap&&<small className="gap"> Quote gap</small>}</td><td><meter min={0} max={100} value={s.quality}/>{s.quality}/100</td><td>{s.entry}</td><td>{s.stopLoss}</td><td>{s.tp1}</td><td>{s.tp2}</td><td>{new Date(s.createdAt).toLocaleString()}</td></tr>})}</tbody></table>{!filtered.length&&<p>No setups yet. A connected feed may show waiting conditions below.</p>}</div>
 {scans.length>0&&<details><summary>Scanner activity · {scans.length} streams</summary><div className="table"><table><thead><tr><th>Instrument</th><th>TF</th><th>Quote feed</th><th>Last closed bar</th><th>Result</th></tr></thead><tbody>{scans.map(s=><tr key={s.id}><td>{s.symbol}</td><td>{s.timeframe}</td><td>{now-new Date(s.lastQuoteAt).getTime()<90000?'Fresh':'Stale / market closed'}</td><td>{new Date(s.lastBar).toLocaleString()}</td><td>{s.reason}</td></tr>)}</tbody></table></div></details>}
 </section>;
}
