const fs=require('fs'),crypto=require('crypto'),vm=require('vm'),path=require('path');
const root=path.resolve(__dirname,'..'),resource=path.join(root,'backend/src/main/resources');
const source=fs.readFileSync(path.join(resource,'v28-engine.js'),'utf8'),manifest=JSON.parse(fs.readFileSync(path.join(resource,'v28-provenance.json'),'utf8'));
let checks=0;
for(const [name,expected]of Object.entries(manifest.functions)){
 const start=source.indexOf('function '+name+'('),brace=source.indexOf('{',start);if(start<0)throw Error('Missing '+name);
 let depth=0,end;for(let i=brace;i<source.length;i++){if(source[i]==='{')depth++;else if(source[i]==='}'&&!--depth){end=i+1;break;}}
 const actual=crypto.createHash('sha256').update(source.slice(start,end)).digest('hex');if(actual!==expected)throw Error('Reference formula changed: '+name);checks++;
}
const context=vm.createContext({});vm.runInContext(source,context);const fixture=JSON.parse(fs.readFileSync(path.join(root,'backend/src/test/resources/v28-btc-fixture.json'),'utf8'));
const result=JSON.parse(context.v28Analyze(JSON.stringify(fixture)));
if(result.scan.score!==98||!result.auto||result.auto.entryType!=='MARKET'||result.scan.gates.length!==12)throw Error('v28 reference fixture changed');checks++;
console.log(checks+' v28 provenance/formula checks passed; copied calculations unchanged.');
