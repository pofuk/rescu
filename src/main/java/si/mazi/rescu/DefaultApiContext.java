package si.mazi.rescu;

import java.util.Collections;
import java.util.Map;

public class DefaultApiContext implements ApiContext {

    @Override
    public ClientConfig getClientConfig() {
        return new ClientConfig();
    }

    @Override
    public Map<String, String> getHeaders() {
        return Collections.emptyMap();
    }

}
