package app.harry.portfolio;

import static org.junit.Assert.*;

import org.junit.Test;

public class UpdaterTest {
    @Test
    public void comparesVersions() {
        assertTrue(Updater.newer("1.2.0", "1.1.0"));
        assertTrue(Updater.newer("1.10.0", "1.9.9"));
        assertTrue(Updater.newer("1.0.0", "0.0.0-dev"));
        assertFalse(Updater.newer("1.2.0", "1.2.0"));
        assertFalse(Updater.newer("1.1.9", "1.2.0"));
    }
}
