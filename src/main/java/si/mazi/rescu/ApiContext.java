package si.mazi.rescu;

import java.util.Map;

public interface ApiContext {

    ClientConfig getClientConfig();

    Map<String, String> getHeaders();
}
