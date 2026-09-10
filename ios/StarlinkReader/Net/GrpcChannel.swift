import Foundation
import NIOCore
import NIOPosix
import NIOHTTP2
import NIOHPACK

struct GrpcError: Error, CustomStringConvertible {
    let message: String
    var description: String { message }
}

/// Мінімальний gRPC-клієнт поверх SwiftNIO з "prior knowledge" HTTP/2 без TLS
/// (h2c) — саме так спілкується локальний API Starlink. iOS не вміє h2c у
/// URLSession (там HTTP/2 підіймається лише через ALPN/TLS), тому тут напряму
/// збирається пайплайн NIOHTTP2 без жодного узгодження протоколу.
///
/// Кадрування gRPC: 1 байт прапорця стиснення + 4 байти довжини (big-endian) +
/// payload — так само, як у GrpcChannel.kt на Android.
final class GrpcChannel {
    private static let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)

    private let host: String
    private let port: Int
    private var connection: Channel?
    private var multiplexer: HTTP2StreamMultiplexer?

    init(host: String, port: Int) {
        self.host = host
        self.port = port
    }

    var target: String { "\(host):\(port)" }

    private func ensureConnected() async throws -> HTTP2StreamMultiplexer {
        if let multiplexer, let connection, connection.isActive { return multiplexer }

        let bootstrap = ClientBootstrap(group: Self.group)
            .connectTimeout(.seconds(3))

        let channel = try await bootstrap.connect(host: host, port: port).get()
        // Пристрій ніколи не ініціює власні потоки до нас — це чистий
        // клієнт, тож callback тут лише заглушка (bare nil тут неоднозначний
        // для компілятора через кілька перевантажень configureHTTP2Pipeline).
        let mux: HTTP2StreamMultiplexer = try await channel.pipeline.configureHTTP2Pipeline(
            mode: .client,
            inboundStreamInitializer: { streamChannel in streamChannel.eventLoop.makeSucceededVoidFuture() }
        ).get()

        self.connection = channel
        self.multiplexer = mux
        return mux
    }

    func close() {
        connection?.close(mode: .all, promise: nil)
        connection = nil
        multiplexer = nil
    }

    /// Надсилає [messages] одним запитом і напівзакриває потік. Для unary-викликів
    /// це звичайний виклик, для bidi-stream (рефлексія) сервер відповідає й
    /// закриває потік.
    func call(fullMethodName: String, messages: [[UInt8]], timeoutSeconds: Int64 = 8) async throws -> [[UInt8]] {
        let mux = try await ensureConnected()

        return try await withCheckedThrowingContinuation { continuation in
            let handler = GrpcStreamHandler(
                host: host,
                path: "/" + fullMethodName,
                messages: messages,
                completion: continuation
            )
            mux.createStreamChannel(promise: nil) { streamChannel in
                streamChannel.pipeline.addHandler(handler)
            }
        }
    }

    func callSingle(fullMethodName: String, message: [UInt8]) async throws -> [UInt8] {
        let frames = try await call(fullMethodName: fullMethodName, messages: [message])
        guard let first = frames.first else {
            throw GrpcError(message: "empty response from \(target)")
        }
        return first
    }
}

/// Один потік HTTP/2 = один gRPC-виклик. Живе рівно стільки, скільки триває
/// один call(): пише заголовки + дані, читає відповідь до кінця потоку.
private final class GrpcStreamHandler: ChannelInboundHandler {
    typealias InboundIn = HTTP2Frame.FramePayload
    typealias OutboundOut = HTTP2Frame.FramePayload

    private let host: String
    private let path: String
    private let messages: [[UInt8]]
    private var completion: CheckedContinuation<[[UInt8]], Error>?

    private var responseBuffer: [UInt8] = []
    private var httpStatus: String?
    private var grpcStatus: String?
    private var grpcMessage: String?
    private var finished = false

    init(host: String, path: String, messages: [[UInt8]], completion: CheckedContinuation<[[UInt8]], Error>) {
        self.host = host
        self.path = path
        self.messages = messages
        self.completion = completion
    }

    func channelActive(context: ChannelHandlerContext) {
        var headers = HPACKHeaders()
        headers.add(name: ":method", value: "POST")
        headers.add(name: ":path", value: path)
        headers.add(name: ":scheme", value: "http")
        headers.add(name: ":authority", value: host)
        headers.add(name: "te", value: "trailers")
        headers.add(name: "content-type", value: "application/grpc+proto")
        headers.add(name: "grpc-accept-encoding", value: "identity")
        headers.add(name: "user-agent", value: "starlink-reader-ios/1.0")

        context.write(wrapOutboundOut(.headers(.init(headers: headers))), promise: nil)

        var buffer = context.channel.allocator.buffer(capacity: 256)
        for message in messages {
            buffer.writeInteger(UInt8(0)) // compression flag: identity
            buffer.writeInteger(UInt32(message.count))
            buffer.writeBytes(message)
        }
        context.writeAndFlush(
            wrapOutboundOut(.data(.init(data: .byteBuffer(buffer), endStream: true))),
            promise: nil
        )
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let payload = unwrapInboundIn(data)
        switch payload {
        case .headers(let headersPayload):
            let headers = headersPayload.headers
            if let status = headers.first(name: ":status") { httpStatus = status }
            if let status = headers.first(name: "grpc-status") { grpcStatus = status }
            if let message = headers.first(name: "grpc-message") { grpcMessage = message }
            if headersPayload.endStream { finish(context: context) }

        case .data(let dataPayload):
            if case .byteBuffer(let buf) = dataPayload.data {
                responseBuffer.append(contentsOf: buf.readableBytesView)
            }
            if dataPayload.endStream { finish(context: context) }

        default:
            break
        }
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        guard !finished else { return }
        finished = true
        completion?.resume(throwing: error)
        completion = nil
        context.close(promise: nil)
    }

    private func finish(context: ChannelHandlerContext) {
        guard !finished, let completion else { return }
        finished = true
        self.completion = nil

        if let httpStatus, httpStatus != "200" {
            completion.resume(throwing: GrpcError(message: "HTTP \(httpStatus)"))
        } else if let grpcStatus, grpcStatus != "0" {
            completion.resume(throwing: GrpcError(message: "grpc-status \(grpcStatus): \(grpcMessage ?? "")"))
        } else {
            completion.resume(returning: Self.unframe(responseBuffer))
        }
        context.close(promise: nil)
    }

    private static func unframe(_ data: [UInt8]) -> [[UInt8]] {
        var out: [[UInt8]] = []
        var i = 0
        while i + 5 <= data.count {
            let compressed = data[i]
            let len = (Int(data[i + 1]) << 24) | (Int(data[i + 2]) << 16) | (Int(data[i + 3]) << 8) | Int(data[i + 4])
            i += 5
            if len < 0 || i + len > data.count { break }
            if compressed == 0 { out.append(Array(data[i..<i + len])) }
            i += len
        }
        return out
    }
}
