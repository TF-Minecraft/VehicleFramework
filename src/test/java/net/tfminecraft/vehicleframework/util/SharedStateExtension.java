package net.tfminecraft.vehicleframework.util;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Registered for every test (src/test/resources/META-INF/services). Each test starts with no
 * block types learned, since mocked blocks need not match their type as real ones do, and ends
 * with every queued log line written and its file closed before its temporary folder is deleted.
 */
public class SharedStateExtension implements BeforeEachCallback, AfterEachCallback {

	@Override
	public void beforeEach(ExtensionContext context) {
		LocationChecker.forgetBlockTypes();
	}

	@Override
	public void afterEach(ExtensionContext context) {
		LogFileWriter.flush();
	}
}
