const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(__dirname + '/../app/src/main/assets/pixiv-task.js', 'utf8');
const csrf = 'abcdefghijklmnopqrstuvwxyz012345';
const tick = async (n=30) => { for(let i=0;i<n;i++) await new Promise(setImmediate); };
function fixture(options={}) {
  const requests=[], reports=[], events=[], saved=new Map();
  let reads=0, writes=0, maxReads=0, maxWrites=0;
  const deferred=[];
  const ctx={ console, URL, AbortController, setTimeout:(f)=>setImmediate(f), clearTimeout:clearImmediate,
    location:{protocol:'https:',hostname:'www.pixiv.net'},
    document:{ querySelector:s=>s.includes('meta-global-data')?{content:JSON.stringify({token:csrf,userData:{id:'12345'}})}:null, querySelectorAll:()=>[] },
    DOMParser:class{parseFromString(){return {querySelector:()=>null,querySelectorAll:()=>[]};}},
    AndroidBridge:{result:(token,kind,id,json)=>reports.push({token,kind,id,...JSON.parse(json)}),
      submitted:(token,ids)=>{events.push(JSON.parse(ids));return true;},event:(...args)=>events.push(args)},
    fetch:async(url,init={})=>{
      assert(!init.signal.aborted,'no request admitted after abort');
      const post=init.method==='POST';
      if(post) maxWrites=Math.max(maxWrites,++writes); else maxReads=Math.max(maxReads,++reads);
      requests.push({url,init});
      try {
        if(options.hold && options.hold(url,init)) await new Promise(resolve=>deferred.push({url,resolve}));
        else await new Promise(setImmediate);
        if(options.error) {const e=options.error(url,init,requests);if(e instanceof Error)throw e;if(e)return res(e,null);}
        if(url.startsWith('/ajax/user/extra'))return res(200,{following:1,followers:2});
        if(url.includes('/profile/all'))return res(options.probeStatus||200,{});
        if(url.startsWith('/ajax/illust/')) {
          const id=url.match(/illust\/(\d+)/)[1];
          if(options.missing===id)return res(404,null);
          const value=saved.has(id)?saved.get(id):(options.already?{id:'b'+id,private:!!options.initialPrivate}:null);
          // Bookmark identifiers intentionally differ from artwork identifiers.
          return res(200,{title:id,bookmarkData:value && {...value,id:value.id.replace('b','9')}});
        }
        const body=JSON.parse(init.body);
        if(url.endsWith('/add'))saved.set(body.illust_id,{id:'b'+body.illust_id,private:!!body.restrict});
        else if(url.endsWith('/remove'))for(const bid of body.bookmarkIds)saved.set(bid.slice(1),null);
        else if(url.endsWith('/edit_restrict'))for(const bid of body.bookmarkIds)saved.set(bid.slice(1),{id:bid,private:body.bookmarkRestrict==='private'});
        else throw new Error('unexpected request '+url);
        return res(200,null);
      }finally{if(post)writes--;else reads--;}
    }
  };
  function res(status,body){return {status,ok:status>=200&&status<300,text:async()=>JSON.stringify({error:false,body})};}
  ctx.window=ctx;vm.createContext(ctx);vm.runInContext(source,ctx);
  return {ctx,requests,reports,events,release:(url)=>{for(let i=deferred.length-1;i>=0;i--)if(!url||deferred[i].url.startsWith(url)){deferred.splice(i,1)[0].resolve();}},limits:()=>({maxReads,maxWrites})};
}
function start(f,ids,kind='bookmark',privateMode=false){
  assert.equal(typeof f.ctx.PixivBatchTask.startBatch,'function','batch queue API required');
  return f.ctx.PixivBatchTask.startBatch(kind,'71',ids,privateMode,csrf);
}
async function waitDone(f){for(let i=0;i<80;i++){await tick(8);if(!f.ctx.PixivBatchTask.isActive())return;}assert.fail('queue did not settle');}
const tests=[];
function test(name,fn){tests.push([name,fn]);}
test('bounded parallel reads and serial mutations, ordered reports and no navigation',async()=>{
  const f=fixture();const ids=['10001','10002','10003','10004','10005','10006'];assert.equal(start(f,ids),true);await waitDone(f);
  assert.deepEqual(f.reports.map(x=>x.id),ids);assert(f.reports.every(x=>x.state==='ok'));
  assert.deepEqual(f.limits(),{maxReads:2,maxWrites:1});
  assert(f.requests.every(x=>x.url.startsWith('/ajax/')));assert.equal(f.requests.filter(x=>x.url.startsWith('/ajax/user/extra')).length,0);
});
test('cancellation resolves bookmark IDs in batches of at most five and verifies all',async()=>{
  const f=fixture({already:true});const ids=Array.from({length:12},(_,i)=>String(10001+i));start(f,ids,'unbookmark');await waitDone(f);
  const batches=f.requests.filter(x=>x.url.endsWith('/remove')).map(x=>JSON.parse(x.init.body).bookmarkIds);
  assert.deepEqual(batches.map(x=>x.length),[5,5,2]);assert.deepEqual(batches.flat(),ids.map(id=>'9'+id));
  assert.equal(f.reports.length,12);assert(f.reports.every(x=>x.state==='removed'));assert(f.limits().maxReads<=2);assert.equal(f.limits().maxWrites,1);
});
test('pause admits no new requests and resume preserves the queue',async()=>{
  const f=fixture({hold:url=>url.startsWith('/ajax/illust/')});start(f,['10001','10002','10003']);await tick();
  f.ctx.PixivBatchTask.pause();f.release();await tick();const count=f.requests.length;assert.equal(count,2);
  await tick();assert.equal(f.requests.length,count);assert(f.ctx.PixivBatchTask.isActive());
  // Further requests must no longer be held.
  f.ctx.fetch=((original)=>async(...args)=>{f.release();return original(...args);})(f.ctx.fetch);
  f.ctx.PixivBatchTask.resume();for(let i=0;i<40;i++){f.release();await tick();}await waitDone(f);assert.equal(f.reports.length,3);
});
test('stop aborts pending reads, invalidates late callbacks and refuses overlapping start',async()=>{
  const f=fixture({hold:()=>true});assert(start(f,['10001','10002']));await tick();assert.equal(start(f,['20001']),false);
  f.ctx.PixivBatchTask.cancel();assert(f.requests.every(x=>x.init.signal.aborted));f.release();await tick();assert.equal(f.reports.length,0);assert(!f.ctx.PixivBatchTask.isActive());
});
test('stop after mutation submission reports pending verification without replay',async()=>{
  const f=fixture({hold:(url,init)=>init.method==='POST'});start(f,['10001']);await tick();assert.equal(f.events.length,1);
  f.ctx.PixivBatchTask.cancel();assert.equal(f.reports[0].state,'pending');f.release();await tick();assert.equal(f.reports.length,1);
  assert.equal(f.requests.filter(x=>x.init.method==='POST').length,1);
});
test('verification network failure is pending for add, conversion and remove',async()=>{
  for(const kind of ['bookmark','unbookmark','convert']) {
    const f=fixture({already:kind!=='bookmark',initialPrivate:false,error:(url,init,requests)=>url.startsWith('/ajax/illust/')&&requests.some(x=>x.init.method==='POST')?new Error('offline'):null});
    start(f,['10001'],kind==='unbookmark'?'unbookmark':'bookmark',true);await waitDone(f);
    assert.equal(f.reports[0].state,'pending',kind);assert.equal(f.requests.filter(x=>x.init.method==='POST').length,1);
  }
});
test('artist probes propagate 429 and 403 and stop further mutation',async()=>{
  for(const status of [429,403]){const f=fixture({missing:'10001',probeStatus:status});start(f,['10001','10002']);await waitDone(f);
    assert.equal(f.reports[0].state,status===429?'blocked':'auth');assert.equal(f.requests.filter(x=>x.init.method==='POST').length,0);
  }
});
test('batch remove errors stop without replay and mark all affected records pending',async()=>{
  const f=fixture({already:true,error:url=>url.endsWith('/remove')?500:null});start(f,['10001','10002','10003','10004','10005','10006'],'unbookmark');await waitDone(f);
  assert.equal(f.requests.filter(x=>x.init.method==='POST').length,1);assert.equal(f.reports.length,5);assert(f.reports.every(x=>x.state==='pending'));
});
test('per-ID missing errors report in sequence while other IDs continue',async()=>{
  const f=fixture({missing:'10002',probeStatus:404});start(f,['10001','10002','10003']);await waitDone(f);
  assert.deepEqual(f.reports.map(x=>[x.id,x.state]),[['10001','ok'],['10002','missing'],['10003','ok']]);
});
test('rate limit halts new read admissions even inside a five-record window',async()=>{
  const f=fixture({already:true,error:url=>url.startsWith('/ajax/illust/10001')?429:null});
  start(f,['10001','10002','10003','10004','10005','10006'],'unbookmark');await waitDone(f);
  assert(f.requests.length<=2,'at most the two already admitted reads may complete');
  assert.equal(f.requests.filter(x=>x.init.method==='POST').length,0);
});
test('fatal response completes a paused queue without waiting for resume',async()=>{
  const f=fixture({already:true,hold:()=>true,error:url=>url.startsWith('/ajax/illust/10001')?429:null});
  start(f,['10001','10002','10003','10004','10005'],'unbookmark');await tick();
  f.ctx.PixivBatchTask.pause();
  f.release('/ajax/illust/10002');await tick(); // Second worker now waits to admit its next read.
  f.release('/ajax/illust/10001');await tick();
  assert(!f.ctx.PixivBatchTask.isActive(),'fatal read must stop even when another worker is waiting for resume');
  assert.equal(f.requests.length,2);assert.equal(f.reports[0].state,'blocked');
});
test('verification rate limit retains pending status and limit reason',async()=>{
  const f=fixture({error:(url,init,requests)=>url.startsWith('/ajax/illust/')&&requests.some(x=>x.init.method==='POST')?429:null});start(f,['10001','10002']);await waitDone(f);
  assert.equal(f.reports[0].state,'pending');assert.equal(f.reports[0].cause,'blocked');assert.equal(f.requests.filter(x=>x.init.method==='POST').length,1);
});
(async()=>{let failures=0;for(const [name,fn]of tests){try{await fn();console.log('PASS '+name);}catch(e){failures++;console.error('FAIL '+name+'\n'+e.stack);}}if(failures)process.exitCode=1;})();
