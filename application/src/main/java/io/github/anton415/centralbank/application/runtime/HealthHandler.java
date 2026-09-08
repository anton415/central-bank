package io.github.anton415.centralbank.application.runtime;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/** Обрабатывает проверку доступности HTTP-сервера. */
public final class HealthHandler extends Handler.Abstract.NonBlocking {

  @Override
  public boolean handle(Request request, Response response, Callback callback) {
    if (!"/health".equals(Request.getPathInContext(request))) {
      return false;
    }

    response.setStatus(200);
    response.getHeaders().put("Content-Type", "text/plain; charset=UTF-8");
    // Запись сама завершит callback; повторно вызывать succeeded() нельзя.
    Content.Sink.write(response, true, "UP\n", callback);
    return true;
  }
}
