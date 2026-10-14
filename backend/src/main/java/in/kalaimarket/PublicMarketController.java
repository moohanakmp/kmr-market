package in.kalaimarket;
import java.util.*;import com.fasterxml.jackson.databind.JsonNode;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/market") public class PublicMarketController {
 private final PublicSignalService service;private final PublicMarketData data;
 public PublicMarketController(PublicSignalService s,PublicMarketData d){service=s;data=d;}
 @GetMapping("/public-status") public Map<String,Object>status(){return Map.of("enabled",service.enabled(),"engine","KMR v28 Smart Engine v3","signalSource","PUBLIC_MARKET_DATA","mt5Purpose","ORDER_EXECUTION_ONLY","errors",data.errors());}
 @GetMapping("/analysis/{symbol}") public JsonNode analysis(@PathVariable String symbol){return service.analysis(symbol);}
 @PostMapping("/public-snapshot") public JsonNode snapshot(@RequestBody PublicSnapshot input){return service.ingest(input);}
 @ExceptionHandler(IllegalArgumentException.class) public org.springframework.http.ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){return org.springframework.http.ResponseEntity.badRequest().body(Map.of("message",e.getMessage()==null?"Invalid public snapshot":e.getMessage()));}
}
