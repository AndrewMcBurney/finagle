package com.twitter.finagle.http2.transport

import com.twitter.finagle.http2.transport.Http2ClientDowngrader.{Message, Rst}
import com.twitter.finagle.stats.InMemoryStatsReceiver
import io.netty.buffer.{Unpooled, ByteBuf}
import io.netty.channel._
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http._
import io.netty.util.concurrent.PromiseCombiner
import java.nio.charset.StandardCharsets
import org.scalatest.FunSuite

class AdapterProxyChannelHandlerTest extends FunSuite {
  val moon = Unpooled.copiedBuffer("goodnight moon", StandardCharsets.UTF_8)
  val stars = Unpooled.copiedBuffer("goodnight stars", StandardCharsets.UTF_8)
  val fullRequest = new DefaultFullHttpRequest(
    HttpVersion.HTTP_1_1,
    HttpMethod.GET,
    "twitter.com",
    moon
  )
  val fullResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, moon)
  val request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "twitter.com")
  val dataReq = new DefaultHttpContent(moon)
  val starsReq = new DefaultHttpContent(stars)
  val messageReq = Message(fullRequest, 3)
  val messageRep = Message(fullResponse, 3)

  def validateObject(
    msg: Message,
    streamId: Int,
    isRequest: Boolean,
    content: Option[ByteBuf]
  ): Boolean = {
    val Message(obj, id) = msg
    if (id != streamId) return false
    obj match {
      case _: HttpRequest if !isRequest => return false
      case _: HttpResponse if isRequest => return false
      case _ =>
    }
    obj match {
      case _: HttpContent if !content.isDefined => false
      case data: HttpContent => data.content == content.get
      case _ => true
    }
  }

  test("writes things through") {
    val handler = new AdapterProxyChannelHandler({ pipeline =>
      ()
    })
    val channel = new EmbeddedChannel()
    channel.pipeline.addLast(handler)
    channel.writeOutbound(messageReq)
    assert(channel.readOutbound[Message]() == messageReq)
  }

  test("reads things through") {
    val handler = new AdapterProxyChannelHandler({ pipeline =>
      ()
    })
    val channel = new EmbeddedChannel()
    channel.pipeline.addLast(handler)
    channel.writeInbound(messageReq)
    assert(channel.readInbound[Message]() == messageReq)
  }

  test("disaggregates reads") {
    val handler = new AdapterProxyChannelHandler({ pipeline =>
      pipeline.addLast(new Disaggregator())
    })
    val channel = new EmbeddedChannel()
    channel.pipeline.addLast(handler)
    channel.writeInbound(messageReq)

    assert(validateObject(channel.readInbound[Message](), 3, true, None))
    assert(validateObject(channel.readInbound[Message](), 3, false, Some(moon)))
  }

  test("disaggregates writes") {
    val handler = new AdapterProxyChannelHandler({ pipeline =>
      pipeline.addLast(new Disaggregator())
    })
    val channel = new EmbeddedChannel()
    channel.pipeline.addLast(handler)
    channel.writeOutbound(messageReq)

    assert(validateObject(channel.readOutbound[Message](), 3, true, None))
    assert(validateObject(channel.readOutbound[Message](), 3, false, Some(moon)))
  }

  test("aggregates writes") {
    val handler = new AdapterProxyChannelHandler({ pipeline =>
      pipeline.addLast(new Aggregator())
    })
    val channel = new EmbeddedChannel()
    channel.pipeline.addLast(handler)

    channel.writeOutbound(Message(request, 3))
    channel.writeOutbound(Message(dataReq, 3))
    assert(validateObject(channel.readOutbound[Message](), 3, true, Some(moon)))
  }

  test("different handlers for different streams") {
    val handler = new AdapterProxyChannelHandler({ pipeline =>
      pipeline.addLast(new Aggregator())
    })
    val channel = new EmbeddedChannel()
    channel.pipeline.addLast(handler)

    channel.writeOutbound(Message(request, 3))
    channel.writeOutbound(Message(request, 5))
    assert(channel.outboundMessages.isEmpty)

    channel.writeOutbound(Message(dataReq, 3))
    assert(validateObject(channel.readOutbound[Message](), 3, true, Some(moon)))
    assert(channel.outboundMessages.isEmpty)

    channel.writeOutbound(Message(starsReq, 5))
    assert(validateObject(channel.readOutbound[Message](), 5, true, Some(stars)))
    assert(channel.outboundMessages.isEmpty)
  }

  test("streams are torn down eventually") {
    val handler = new AdapterProxyChannelHandler({ pipeline =>
      ()
    })
    val channel = new EmbeddedChannel()
    channel.pipeline.addLast(handler)

    channel.writeOutbound(messageReq)
    assert(handler.numConnections == 1)
    channel.writeInbound(messageRep)
    assert(handler.numConnections == 0)
  }

  test("streams aren't torn down until we actually get a last item") {
    val stats = new InMemoryStatsReceiver
    val handler = new AdapterProxyChannelHandler({ pipeline =>
      ()
    }, stats)
    val channel = new EmbeddedChannel()
    channel.pipeline.addLast(handler)

    val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "twitter.com")
    val rep = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
    val last = new DefaultLastHttpContent()

    channel.writeOutbound(Message(req, 3))
    assert(handler.numConnections == 1)
    channel.writeInbound(Message(rep, 3))
    assert(handler.numConnections == 1)
    channel.writeInbound(Message(last, 3))
    assert(handler.numConnections == 1)
    channel.writeOutbound(Message(last, 3))
    assert(handler.numConnections == 0)
  }

  test("streams are torn down when we receive rst") {
    val handler = new AdapterProxyChannelHandler({ pipeline =>
      ()
    })
    val channel = new EmbeddedChannel()
    channel.pipeline.addLast(handler)

    val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "twitter.com")
    val rep = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
    val last = new DefaultLastHttpContent()

    channel.writeOutbound(Message(req, 3))
    assert(handler.numConnections == 1)
    channel.writeInbound(Message(rep, 3))
    assert(handler.numConnections == 1)
    channel.writeOutbound(Rst(3, 0x8))
    assert(handler.numConnections == 0)
    channel.writeInbound(Message(last, 3))
    assert(handler.numConnections == 0)
  }

  test("streams are torn down when we send rst") {
    val handler = new AdapterProxyChannelHandler({ pipeline =>
      ()
    })
    val channel = new EmbeddedChannel()
    channel.pipeline.addLast(handler)

    val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "twitter.com")
    val rep = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
    val last = new DefaultLastHttpContent()

    channel.writeOutbound(Message(req, 3))
    assert(handler.numConnections == 1)
    channel.writeInbound(Message(rep, 3))
    assert(handler.numConnections == 1)
    channel.writeInbound(Rst(3, 0x8))
    assert(handler.numConnections == 0)
    intercept[AdapterProxyChannelHandler.WriteToNackedStreamException] {
      channel.writeOutbound(Message(last, 3))
    }
  }

  test("channel gauge is accurate") {
    val stats = new InMemoryStatsReceiver()
    val handler = new AdapterProxyChannelHandler({ pipeline =>
      ()
    }, stats)
    val channel = new EmbeddedChannel()
    channel.pipeline.addLast(handler)

    val channelsGauge = stats.gauges(Seq("channels"))
    assert(channelsGauge() == 0.0)

    val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "twitter.com")
    val rep = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
    val last = new DefaultLastHttpContent()

    channel.writeOutbound(Message(req, 3))
    channel.writeOutbound(Message(last, 3))
    assert(channelsGauge() == 1.0)

    channel.writeOutbound(Message(req, 5))
    assert(channelsGauge() == 2.0)

    channel.writeOutbound(Message(last, 5))
    channel.writeInbound(Message(rep, 3))
    channel.writeInbound(Message(last, 3))
    assert(channelsGauge() == 1.0)

    channel.writeInbound(Message(rep, 5))
    channel.writeInbound(Message(last, 5))
    assert(channelsGauge() == 0.0)
  }

  test("close closes the underlying connection") {
    val handler = new AdapterProxyChannelHandler({ pipeline =>
      ()
    })
    val channel = new EmbeddedChannel()
    channel.pipeline.addLast(handler)

    channel.close()
    assert(!channel.isOpen)
  }
}

class Aggregator extends ChannelDuplexHandler {
  var curWrite: Option[(HttpRequest, ChannelPromise)] = None
  var curRead: Option[HttpRequest] = None

  override def write(ctx: ChannelHandlerContext, msg: Object, promise: ChannelPromise): Unit = {
    (curWrite, msg) match {
      case (Some((x, oldP)), data: HttpContent) =>
        promise.addListener(new ChannelPromiseNotifier(oldP))
        ctx.write(
          new DefaultFullHttpRequest(
            x.getProtocolVersion,
            x.getMethod,
            x.getUri,
            data.content
          ),
          promise
        )
        curWrite = None
      case (None, req: HttpRequest) =>
        curWrite = Some((req, promise))
      case _ =>
        throw new Exception("boom! expected a different message.")
    }
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Object): Unit = {
    (curRead, msg) match {
      case (Some(x), data: HttpContent) =>
        ctx.fireChannelRead(
          new DefaultFullHttpRequest(x.getProtocolVersion, x.getMethod, x.getUri, data.content)
        )
        curRead = None
      case (None, req: HttpRequest) =>
        curRead = Some(req)
      case _ =>
        throw new Exception("boom! expected a different message.")
    }
  }
}

class Disaggregator extends ChannelDuplexHandler {
  override def write(ctx: ChannelHandlerContext, msg: Object, promise: ChannelPromise): Unit = {
    msg match {
      case fullReq: FullHttpRequest =>
        val req = new DefaultHttpRequest(
          fullReq.getProtocolVersion,
          fullReq.getMethod,
          fullReq.getUri
        )
        val data = new DefaultHttpContent(fullReq.content)
        val combiner = new PromiseCombiner()
        val reqP = ctx.newPromise()
        val dataP = ctx.newPromise()
        combiner.add(reqP)
        combiner.add(dataP)
        combiner.finish(promise)
        ctx.write(req, reqP)
        ctx.write(data, dataP)
    }
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Object): Unit = {
    msg match {
      case fullReq: FullHttpRequest =>
        val req = new DefaultHttpRequest(
          fullReq.getProtocolVersion,
          fullReq.getMethod,
          fullReq.getUri
        )
        val data = new DefaultHttpContent(fullReq.content)
        ctx.fireChannelRead(req)
        ctx.fireChannelRead(data)
    }
  }
}
