package in.kalaimarket;
import jakarta.validation.Valid;import org.springframework.web.bind.annotation.*;import org.springframework.http.HttpStatus;import java.util.Map;
@RestController @RequestMapping("/api/orders") public class OrderController {
 private final OrderService service;public OrderController(OrderService s){service=s;}
 @PostMapping("/manual") @ResponseStatus(HttpStatus.CREATED) public AutoSignal manual(@Valid @RequestBody ManualOrderRequest r){return service.manual(r);}
 @PostMapping("/{id}/cancel") public Map<String,String> cancel(@PathVariable long id){service.cancel(id);return Map.of("status","CANCELLATION_REQUESTED");}
}
