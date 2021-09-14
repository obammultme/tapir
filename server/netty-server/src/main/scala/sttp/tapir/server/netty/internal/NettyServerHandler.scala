package sttp.tapir.server.netty.internal

import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.{ChannelFutureListener, ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.handler.codec.http._
import sttp.tapir.model.ServerResponse
import sttp.tapir.server.netty.{NettyServerRequest, Route}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class NettyServerHandler(route: Route)(implicit val ec: ExecutionContext) extends SimpleChannelInboundHandler[FullHttpRequest] {

  private def toHttpResponse(interpreterResponse: ServerResponse[ByteBuf], req: FullHttpRequest): FullHttpResponse = {
    val res = new DefaultFullHttpResponse(
      req.protocolVersion(),
      HttpResponseStatus.valueOf(interpreterResponse.code.code),
      interpreterResponse.body.getOrElse(Unpooled.EMPTY_BUFFER)
    )

    interpreterResponse.headers
      .groupBy(_.name)
      .foreach { case (k, v) =>
        res.headers().set(k, v.map(_.value).asJava)
      }

    res.headers().set(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes())

    res
  }

  override def channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest): Unit = {
    if (HttpUtil.is100ContinueExpected(request)) {
      ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE))
      ()
    } else {
      val req = request.retainedDuplicate()

      route(NettyServerRequest(req))
        .map {
          case Some(response) => response
          case None           => ServerResponse(sttp.model.StatusCode.NotFound, Nil, None)
        }
        .map(toHttpResponse(_, request))
        .map(flushResponse(ctx, request, _))
        .onComplete {
          case Failure(exception) => ctx.fireExceptionCaught(exception)
          case Success(_)         => ()
        }

      ()
    }
  }

  def flushResponse(ctx: ChannelHandlerContext, req: HttpRequest, res: HttpResponse): Unit = {
    if (!HttpUtil.isKeepAlive(req)) {
      ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE)
      ()
    } else {
      res.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
      ctx.writeAndFlush(res)
      ()
    }
  }
}