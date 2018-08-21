package com.sinnerschrader.aem.react;

import com.sinnerschrader.aem.react.ReactScriptEngine.RenderResult;
import com.sinnerschrader.aem.react.api.Cqx;
import com.sinnerschrader.aem.react.exception.TechnicalException;
import com.sinnerschrader.aem.react.loader.HashedScript;
import com.sinnerschrader.aem.react.loader.ScriptCollectionLoader;
import com.sinnerschrader.aem.react.metrics.ComponentMetricsService;
import com.sinnerschrader.aem.react.metrics.MetricsHelper;
import jdk.nashorn.api.scripting.ScriptObjectMirror;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

/**
 *
 * This Javascript engine can render ReactJs component in nashorn.
 *
 * @author stemey
 *
 */
public class JavascriptEngine {
	private ScriptCollectionLoader loader;
	private ScriptEngine engine;
	private Map<String, String> scriptChecksums;
	private ComponentMetricsService metricsService;
	private boolean initialized = false;
	private Object sling;
	private ScriptObjectMirror scriptObject;

	private static final Logger LOGGER = LoggerFactory.getLogger(JavascriptEngine.class);

	public static class Console {

		public Console() {
			super();
		}

		public void debug(String statement, Object... args) {
			LOGGER.debug(statement, args);
		}

		public void debug(String statement, Object error) {
			LOGGER.debug(statement, error);
		}

		public void log(String statement) {
			LOGGER.info(statement);
		}

		public void log(String statement, Object error) {
			LOGGER.info(statement, error);
		}

		public void info(String statement) {
			LOGGER.info(statement);
		}

		public void info(String statement, Object error) {
			LOGGER.info(statement, error);
		}

		public void error(String statement) {
			LOGGER.error(statement);
		}

		public void error(String statement, Object error) {
			LOGGER.error(statement, error);
		}

		public void warn(String statement) {
			LOGGER.warn(statement);
		}

		public void warn(String statement, Object error) {
			LOGGER.warn(statement, error);
		}

		public void time(String name) {
			MetricsHelper.getCurrent().timer(name);
		}

		public void timeEnd(String name) {
			MetricsHelper.getCurrent().timerEnd(name);
		}

	}

	public static class Print extends Writer {
		@Override
		public void write(char[] cbuf, int off, int len) throws IOException {
			LOGGER.error(new String(cbuf, off, len));
		}

		@Override
		public void flush() throws IOException {

		}

		@Override
		public void close() throws IOException {

		}
	}

	public JavascriptEngine(ScriptCollectionLoader loader, Object sling) {
		this.loader = loader;
		this.sling = sling;
	}

	/**
	 * initialize this instance. creates a javascript engine and loads the
	 * javascript files. Instances of this class are not thread-safe.
	 *
	 */
	public synchronized void initialize(boolean forceInitialization) {
		if(this.initialized && !forceInitialization) {
			return;
		}

		ScriptEngineManager scriptEngineManager = new ScriptEngineManager(null);
		engine = scriptEngineManager.getEngineByName("nashorn");
		engine.getContext().setErrorWriter(new Print());
		engine.getContext().setWriter(new Print());
		engine.put("console", new Console());
		engine.put("Sling", this.sling);
		loadJavascriptLibrary();

		this.initialized = true;
	}

	private void loadJavascriptLibrary() {
		long start = System.currentTimeMillis();
		scriptChecksums = new HashMap<>();
		Iterator<HashedScript> iterator = loader.iterator();
		while (iterator.hasNext()) {
			try {
				HashedScript next = iterator.next();
                scriptObject = (ScriptObjectMirror)engine.eval(next.getScript());
                scriptChecksums.put(next.getId(), next.getChecksum());
			} catch (ScriptException e) {
				throw new TechnicalException("cannot eval library script", e);
			}
		}
		LOGGER.debug("JavascriptEngine.loadJavascriptLibrary took: " + (System.currentTimeMillis() - start) + "ms");
	}

	/**
	 * render the given react component
	 *
	 * @param path
	 * @param resourceType
	 * @param wcmmode
	 * @param cqx
	 *            API object for current request
	 * @return
	 */
	public RenderResult render(String path, String resourceType, String wcmmode, Cqx cqx, boolean renderAsJson,
			Object reactContext, List<String> selectors) {
		long startTime = System.currentTimeMillis();

		if(!this.initialized) {
			throw new IllegalStateException("JavascriptEngine is not initialized");
		}

		try {
            Object value = scriptObject.callMember("renderReactComponent", path, resourceType, wcmmode,
                    renderAsJson, null, selectors.toArray(new String[selectors.size()]), cqx);

			RenderResult result = new RenderResult();
			result.html = (String) ((Map<String, Object>) value).get("html");
			result.cache = ((Map<String, Object>) value).get("state").toString();
			result.reactContext = ((Map<String, Object>) value).get("reactContext");
			LOGGER.debug("JavascriptEngine.render took: " + (System.currentTimeMillis() - startTime) + "ms");
			return result;
		} catch (Exception e) {
			LOGGER.error("error", e);
			throw new TechnicalException("cannot render react on server", e);
		}
	}

	public ScriptEngine getEngine() {
		return engine;
	}

	public boolean isScriptsChanged() {
		Iterator<HashedScript> iterator = loader.iterator();
		if (!iterator.hasNext() && scriptChecksums.size() > 0) {
			return true;
		}
		while (iterator.hasNext()) {
			HashedScript next = iterator.next();
			String checksum = scriptChecksums.get(next.getId());
			if (checksum == null || !checksum.equals(next.getChecksum())) {
				return true;
			}
		}
		return false;

	}

}
