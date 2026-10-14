package in.kalaimarket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController @RequestMapping("/api/audit")
public class SignalAuditController {
 private final JdbcTemplate db; private final ObjectMapper json;
 public SignalAuditController(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 @GetMapping public Map<String,Object> list(
  @RequestParam(required=false) String channel,@RequestParam(required=false) String symbol,
  @RequestParam(required=false) Long signalId,@RequestParam(required=false) String eventType,
  @RequestParam(required=false) Long beforeId,@RequestParam(defaultValue="50") int limit){
  if(limit<1||limit>200||beforeId!=null&&beforeId<1||signalId!=null&&signalId<1)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use limit 1–200 and positive IDs");
  if(channel!=null&&!Set.of("AUTO","MANUAL","LOW_PROFIT").contains(channel))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Channel must be AUTO, MANUAL or LOW_PROFIT");
  StringBuilder sql=new StringBuilder("SELECT * FROM signal_activity_audit WHERE true");var args=new ArrayList<Object>();
  if(channel!=null){sql.append(" AND channel=?");args.add(channel);}
  if(symbol!=null){sql.append(" AND symbol=?");args.add(symbol);}
  if(signalId!=null){sql.append(" AND signal_id=?");args.add(signalId);}
  if(eventType!=null){sql.append(" AND event_type=?");args.add(eventType);}
  if(beforeId!=null){sql.append(" AND id<?");args.add(beforeId);}
  sql.append(" ORDER BY id DESC LIMIT ?");args.add(limit+1);
  var rows=db.query(sql.toString(),(rs,n)->{
   Map<String,Object> row=new LinkedHashMap<>();
   for(String key:List.of("id","signal_id","channel","symbol","timeframe","event_type","actor","initiated_by","previous_status","new_status","reason"))row.put(key,rs.getObject(key));
   for(String key:List.of("occurred_at","observed_at")){var t=rs.getTimestamp(key);row.put(key,t==null?null:t.toInstant().toString());}
   for(String key:List.of("before_state","after_state","decision_context")){var value=rs.getString(key);try{row.put(key,value==null?null:json.readTree(value));}catch(Exception e){throw new IllegalStateException("Unreadable audit evidence",e);}}
   return row;
  },args.toArray());
  boolean more=rows.size()>limit;var items=more?rows.subList(0,limit):rows;
  Map<String,Object> result=new LinkedHashMap<>();result.put("items",items);result.put("nextBeforeId",more?items.get(items.size()-1).get("id"):null);return result;
 }
}
