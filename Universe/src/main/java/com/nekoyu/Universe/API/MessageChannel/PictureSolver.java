package com.nekoyu.Universe.API.MessageChannel;

import java.io.IOException;
import java.net.URI;

public interface PictureSolver {
    String getDescription(URI uri) throws IOException;
}
