package ar.com.leo;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Util {

    public static String getJarFolder() throws URISyntaxException {
        URI uri = Launcher.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI();

        Path path = Paths.get(uri); // esto soporta UNC
        return path.getParent().toString();
    }

}