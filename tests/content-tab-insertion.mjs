// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";
import test from "node:test";
const source = await readFile(new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
const start = source.indexOf("  runtime.setNavigationDelegate({");
const end = source.indexOf("  const orderedSessionRecords =", start);
function fixture() {
  const tabs = [], records = new Map(); let delegate, selected = "C";
  const add = id => {
    const record = {session:{id},tab:{}};
    Object.defineProperty(record.tab,"nextSibling",{get:()=>tabs[tabs.indexOf(record.tab)+1]??null});
    tabs.push(record.tab); records.set(id,record); return record;
  };
  ["A","B","C"].forEach(add);
  vm.runInNewContext(source.slice(start,end), {
    runtime:{setNavigationDelegate: value => {delegate=value;}}, records,
    createView: () => ({}), adoptSession: session => add(session.id),
    selectSession: record => {selected=record.session.id;},
    sessionTabs:{insertBefore(tab,before){tabs.splice(tabs.indexOf(tab),1);tabs.splice(before?tabs.indexOf(before):tabs.length,0,tab);}},
    synchronizeSessionOrder(){const order=tabs.map(tab=>[...records.values()].find(record=>record.tab===tab));records.clear();for(const r of order)records.set(r.session.id,r);},
  });
  return {delegate,records,order:()=>[...records.keys()],selected:()=>selected};
}
test("content-created tab inserts beside its live source before activation",()=>{
 for (const activate of [true,false]) {
  const f=fixture(); f.delegate.onSessionCreated({session:{id:"N"},activate,openerSessionId:"A"});
  assert.deepEqual(f.order(),["A","N","B","C"]); assert.equal(f.selected(),activate?"N":"C");
 }
});
test("external or unknown source retains ordinary append behavior",()=>{
 const f=fixture(); f.delegate.onSessionCreated({session:{id:"N"},activate:true,openerSessionId:null});
 assert.deepEqual(f.order(),["A","B","C","N"]);
});
