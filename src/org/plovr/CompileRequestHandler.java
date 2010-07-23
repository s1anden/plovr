package org.plovr;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.JSError;
import com.google.javascript.jscomp.Result;
import com.google.javascript.jscomp.SourceExcerptProvider;
import com.google.template.soy.base.SoySyntaxException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

public final class CompileRequestHandler extends AbstractGetHandler {

  private static final Logger logger = Logger.getLogger(
      CompileRequestHandler.class.getName());

  private final Gson gson;

  private final String plovrJsLib;

  public CompileRequestHandler(CompilationServer server) {
    super(server);

    GsonBuilder gsonBuilder = new GsonBuilder();
    gsonBuilder.registerTypeAdapter(CompilationError.class,
        new CompilationErrorSerializer());
    gson = gsonBuilder.create();

    URL plovrJsLibUrl = Resources.getResource("org/plovr/plovr.js");
    String plovrJsLib;
    try {
      plovrJsLib = Resources.toString(plovrJsLibUrl, Charsets.US_ASCII);
    } catch (IOException e) {
      logger.log(Level.SEVERE, "Error loading errors.js", e);
      plovrJsLib = null;
    }
    this.plovrJsLib = plovrJsLib;
  }

  @Override
  protected void doGet(HttpExchange exchange, QueryData data, Config config) throws IOException {
    // Update these fields as they are responsible for the response that will be
    // written.
    StringBuilder builder = new StringBuilder();
    String contentType;
    int responseCode;

    try {
      if (config.getCompilationMode() == CompilationMode.RAW) {
        String prefix;
        URI referrer = HttpUtil.getReferrer(exchange);
        if (referrer == null) {
          prefix = "http://localhost:9810/";
        } else {
          prefix =
              referrer.getScheme() + "://" + referrer.getHost() + ":9810/";
        }
        Manifest manifest = config.getManifest();
        URI requestUri = exchange.getRequestURI();
        String js = InputFileHandler.getJsToLoadManifest(config,
            manifest, prefix, requestUri.getPath());
        builder.append(js);
      } else {
        compile(config, builder);
      }
    } catch (MissingProvideException e) {
      Preconditions.checkState(builder.length() == 0,
          "Should not write errors to builder if output has already been written");
      writeErrors(config, ImmutableList.of(e.createCompilationError()),
          builder);
    }
    contentType = "text/javascript";
    responseCode = 200;

    Headers responseHeaders = exchange.getResponseHeaders();
    responseHeaders.set("Content-Type", contentType);
    exchange.sendResponseHeaders(responseCode, builder.length());

    Writer responseBody = new OutputStreamWriter(exchange.getResponseBody());
    responseBody.write(builder.toString());
    responseBody.close();
  }

  public static Compilation compile(Config config)
      throws MissingProvideException, CheckedSoySyntaxException {
    try {
      Compilation compilation = config.getManifest().getCompilerArguments(
          config.getModuleConfig());
      CompilerOptions options = config.getCompilerOptions();
      compilation.compile(options);
      return compilation;
    } catch (SoySyntaxException e) {
      throw new CheckedSoySyntaxException(e);
    }
  }

  private void compile(Config config, Appendable builder)
      throws IOException, MissingProvideException {
    Compiler compiler = new Compiler();
    Result result = null;
    try {
      result = compile(config).getResult();
      server.recordSourceMap(config, result.sourceMap);
      server.recordExportsAsExterns(config, result.externExport);
    } catch (MissingProvideException e) {
      writeErrors(config, ImmutableList.of(e.createCompilationError()),
          builder);
      return;
    } catch (CheckedSoySyntaxException e) {
      writeErrors(config, ImmutableList.of(e.createCompilationError()),
          builder);
      return;
    }

    if (result.success) {
      if (config.getCompilationMode() == CompilationMode.WHITESPACE) {
        builder.append("CLOSURE_NO_DEPS = true;\n");
      }
      builder.append(compiler.toSource());
    }

    // TODO(bolinfest): Check whether writing out the plovr library confuses the
    // source map. Hopefully adding it after the compiled code will prevent it
    // from messing with the line numbers.

    // Write out the plovr library, even if there are no warnings.
    // It is small, and it exports some symbols that may be of use to
    // developers.
    writeErrorsAndWarnings(config, normalizeErrors(result.errors, compiler),
        normalizeErrors(result.warnings, compiler), builder);
  }

  private static List<CompilationError> normalizeErrors(JSError[] errors,
      SourceExcerptProvider sourceExcerptProvider) {
    List<CompilationError> compilationErrors = Lists.newLinkedList();
    for (JSError error : errors) {
      compilationErrors.add(new CompilationError(error, sourceExcerptProvider));
    }
    return compilationErrors;
  }

  private void writeErrors(Config config, List<CompilationError> errors,
      Appendable builder) throws IOException {
    writeErrorsAndWarnings(config, errors,
        ImmutableList.<CompilationError>of(),
        builder);
  }

  private void writeErrorsAndWarnings(Config config,
      List<CompilationError> errors, List<CompilationError> warnings,
      Appendable builder) throws IOException {
    Preconditions.checkNotNull(errors);
    Preconditions.checkNotNull(builder);

    String configIdJsString = gson.toJson(config.getId());
    builder.append(plovrJsLib)
        .append("plovr.addErrors(").append(gson.toJson(errors)).append(");\n")
        .append("plovr.addWarnings(").append(gson.toJson(warnings)).append(");\n")
        .append("plovr.setConfigId(").append(configIdJsString).append(");\n")
        .append("plovr.writeErrorsOnLoad();\n");
  }
}

