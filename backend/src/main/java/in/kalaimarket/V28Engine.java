package in.kalaimarket;
import org.graalvm.polyglot.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.io.*;import java.nio.charset.StandardCharsets;
@Component public class V28Engine implements AutoCloseable {
 private final ObjectMapper json;private Context context;
 public V28Engine(ObjectMapper json){this.json=json;}
 private void initialize(){if(context!=null)return;
  try(InputStream in=getClass().getResourceAsStream("/v28-engine.js")){if(in==null)throw new IllegalStateException("Reference engine missing");
   context=Context.newBuilder("js").allowAllAccess(false).option("engine.WarnInterpreterOnly","false").build();context.eval("js",new String(in.readAllBytes(),StandardCharsets.UTF_8));
  }catch(IOException e){throw new IllegalStateException("Cannot load reference engine",e);}
 }
 public synchronized JsonNode analyze(PublicSnapshot input){input.validate(System.currentTimeMillis());initialize();try{return json.readTree(context.getBindings("js").getMember("v28Analyze").execute(json.writeValueAsString(input.at(System.currentTimeMillis()))).asString());}catch(IOException e){throw new IllegalStateException("Reference engine serialization failed",e);}}
 @jakarta.annotation.PreDestroy public synchronized void close(){if(context!=null){context.close();context=null;}}
}
