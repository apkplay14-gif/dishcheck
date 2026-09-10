import UIKit
import AVFoundation
import Vision
import AudioToolbox

/// Сканування QR / Data Matrix / штрихкоду через Vision (а не
/// AVCaptureMetadataOutput) — свідомо, щоб мати доступ до самих пікселів
/// кадру: кожен другий кадр аналізується з інвертованою яскравістю, бо на
/// коробках Starlink трапляється Data Matrix, надрукований світлим по
/// чорному, а звичайні детектори шукають лише темні модулі на світлому тлі.
final class QrScannerViewController: UIViewController {
    var titleText: String = ""
    var progressText: String = ""
    /// nil — код так і не обрано (скасування/помилка камери).
    var onFinish: ((String?) -> Void)?

    private let session = AVCaptureSession()
    private var device: AVCaptureDevice?
    private let videoOutput = AVCaptureVideoDataOutput()
    private let sessionQueue = DispatchQueue(label: "qr.session")
    private var previewLayer: AVCaptureVideoPreviewLayer?

    private var frameCount: Int64 = 0
    private var paused = false
    private var delivered = false
    private var pendingValue: String?
    private var pendingFormat: String = ""

    private let zoneView = ZoneView()
    private let confirmPanel = UIView()
    private let confirmValueLabel = UILabel()
    private let confirmMetaLabel = UILabel()
    private let torchButton = UIButton(type: .system)
    private let headerTitle = UILabel()
    private let headerProgress = UILabel()
    private let zoneSlider = UISlider()
    private let zoomSlider = UISlider()

    private let ink = UIColor(red: 0x06 / 255, green: 0x08 / 255, blue: 0x0D / 255, alpha: 1)
    private let scrim = UIColor(red: 0x0A / 255, green: 0x0E / 255, blue: 0x16 / 255, alpha: 0.9)
    private let confirmScrim = UIColor(red: 0x0A / 255, green: 0x0E / 255, blue: 0x16 / 255, alpha: 0.95)
    private let accent = UIColor(red: 0x4C / 255, green: 0x8D / 255, blue: 0xFF / 255, alpha: 1)
    private let inkText = UIColor(red: 0xF3 / 255, green: 0xF6 / 255, blue: 0xFB / 255, alpha: 1)
    private let muted = UIColor(red: 0x8A / 255, green: 0x93 / 255, blue: 0xA6 / 255, alpha: 1)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ink
        modalPresentationStyle = .fullScreen
        buildUI()
        checkPermissionAndStart()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
        zoneView.frame = view.bounds
    }

    override var prefersStatusBarHidden: Bool { true }

    // MARK: - UI

    private func buildUI() {
        zoneView.frame = view.bounds
        zoneView.onTap = { [weak self] point in self?.focus(at: point) }
        zoneView.onChanged = { }
        view.addSubview(zoneView)

        let header = UIStackView()
        header.axis = .vertical
        header.spacing = 4
        header.backgroundColor = scrim
        header.isLayoutMarginsRelativeArrangement = true
        header.layoutMargins = UIEdgeInsets(top: 56, left: 20, bottom: 14, right: 20)
        headerProgress.font = .systemFont(ofSize: 12, weight: .semibold)
        headerProgress.textColor = accent
        headerProgress.text = progressText.uppercased()
        headerProgress.isHidden = progressText.isEmpty
        headerTitle.font = .systemFont(ofSize: 21, weight: .semibold)
        headerTitle.textColor = inkText
        headerTitle.text = titleText.isEmpty ? NSLocalizedString("scan_title_default", comment: "") : titleText
        headerTitle.numberOfLines = 2
        header.addArrangedSubview(headerProgress)
        header.addArrangedSubview(headerTitle)
        header.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(header)

        let hint = UILabel()
        hint.text = NSLocalizedString("scan_hint", comment: "")
        hint.font = .systemFont(ofSize: 13)
        hint.textColor = muted
        hint.numberOfLines = 0

        zoneSlider.minimumValue = 0
        zoneSlider.maximumValue = 1
        zoneSlider.value = 0.44
        zoneSlider.addTarget(self, action: #selector(zoneSliderChanged), for: .valueChanged)

        zoomSlider.minimumValue = 0
        zoomSlider.maximumValue = 1
        zoomSlider.value = 0
        zoomSlider.addTarget(self, action: #selector(zoomSliderChanged), for: .valueChanged)

        let zoneRow = labeledSlider(NSLocalizedString("scan_zone", comment: ""), zoneSlider)
        let zoomRow = labeledSlider(NSLocalizedString("scan_zoom", comment: ""), zoomSlider)

        torchButton.setTitle(NSLocalizedString("torch", comment: ""), for: .normal)
        torchButton.setTitleColor(inkText, for: .normal)
        torchButton.isHidden = true
        torchButton.addTarget(self, action: #selector(toggleTorch), for: .touchUpInside)

        let skipButton = UIButton(type: .system)
        skipButton.setTitle(NSLocalizedString("action_skip", comment: ""), for: .normal)
        skipButton.setTitleColor(inkText, for: .normal)
        skipButton.addTarget(self, action: #selector(skipTapped), for: .touchUpInside)

        let bottomButtons = UIStackView(arrangedSubviews: [torchButton, skipButton])
        bottomButtons.axis = .horizontal
        bottomButtons.distribution = .fillEqually

        let footer = UIStackView(arrangedSubviews: [hint, zoneRow, zoomRow, bottomButtons])
        footer.axis = .vertical
        footer.spacing = 6
        footer.backgroundColor = scrim
        footer.isLayoutMarginsRelativeArrangement = true
        footer.layoutMargins = UIEdgeInsets(top: 14, left: 20, bottom: 34, right: 20)
        footer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(footer)

        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: view.topAnchor),
            header.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            header.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            footer.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            footer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            footer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])

        buildConfirmPanel()
    }

    private func labeledSlider(_ text: String, _ slider: UISlider) -> UIView {
        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: 13)
        label.textColor = muted
        label.setContentHuggingPriority(.required, for: .horizontal)
        let row = UIStackView(arrangedSubviews: [label, slider])
        row.axis = .horizontal
        row.spacing = 12
        row.alignment = .center
        return row
    }

    private func buildConfirmPanel() {
        confirmPanel.backgroundColor = confirmScrim
        confirmPanel.isHidden = true
        confirmPanel.frame = view.bounds
        confirmPanel.autoresizingMask = [.flexibleWidth, .flexibleHeight]

        let found = UILabel()
        found.text = NSLocalizedString("scan_found", comment: "")
        found.font = .systemFont(ofSize: 13, weight: .semibold)
        found.textColor = accent
        found.textAlignment = .center

        confirmValueLabel.font = .monospacedSystemFont(ofSize: 22, weight: .regular)
        confirmValueLabel.textColor = inkText
        confirmValueLabel.textAlignment = .center
        confirmValueLabel.numberOfLines = 3

        confirmMetaLabel.font = .systemFont(ofSize: 13)
        confirmMetaLabel.textColor = muted
        confirmMetaLabel.textAlignment = .center

        let verify = UILabel()
        verify.text = NSLocalizedString("scan_verify", comment: "")
        verify.font = .systemFont(ofSize: 13)
        verify.textColor = muted
        verify.textAlignment = .center
        verify.numberOfLines = 0

        let againButton = UIButton(type: .system)
        againButton.setTitle(NSLocalizedString("action_again", comment: ""), for: .normal)
        againButton.setTitleColor(muted, for: .normal)
        againButton.addTarget(self, action: #selector(resumeScanning), for: .touchUpInside)

        let confirmButton = UIButton(type: .system)
        confirmButton.setTitle(NSLocalizedString("action_confirm", comment: ""), for: .normal)
        confirmButton.setTitleColor(accent, for: .normal)
        confirmButton.addTarget(self, action: #selector(confirmTapped), for: .touchUpInside)

        let buttons = UIStackView(arrangedSubviews: [againButton, confirmButton])
        buttons.axis = .horizontal
        buttons.distribution = .fillEqually

        let stack = UIStackView(arrangedSubviews: [found, confirmValueLabel, confirmMetaLabel, verify, buttons])
        stack.axis = .vertical
        stack.spacing = 10
        stack.translatesAutoresizingMaskIntoConstraints = false
        confirmPanel.addSubview(stack)
        view.addSubview(confirmPanel)

        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: confirmPanel.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: confirmPanel.leadingAnchor, constant: 32),
            stack.trailingAnchor.constraint(equalTo: confirmPanel.trailingAnchor, constant: -32),
        ])
    }

    @objc private func zoneSliderChanged() {
        zoneView.sizeFraction = 0.2 + CGFloat(zoneSlider.value) * 0.8
    }

    @objc private func zoomSliderChanged() {
        setZoom(CGFloat(zoomSlider.value))
    }

    @objc private func toggleTorch() {
        guard let device, device.hasTorch else { return }
        try? device.lockForConfiguration()
        device.torchMode = device.torchMode == .on ? .off : .on
        torchButton.setTitle(
            NSLocalizedString(device.torchMode == .on ? "torch_on" : "torch", comment: ""),
            for: .normal
        )
        device.unlockForConfiguration()
    }

    @objc private func skipTapped() {
        finish(nil)
    }

    @objc private func resumeScanning() {
        pendingValue = nil
        confirmPanel.isHidden = true
        paused = false
    }

    @objc private func confirmTapped() {
        if let value = pendingValue { finish(value) }
    }

    private func focus(at point: CGPoint) {
        guard let device, let previewLayer, device.isFocusPointOfInterestSupported else { return }
        let devicePoint = previewLayer.captureDevicePointConverted(fromLayerPoint: point)
        try? device.lockForConfiguration()
        device.focusPointOfInterest = devicePoint
        device.focusMode = .autoFocus
        if device.isExposurePointOfInterestSupported {
            device.exposurePointOfInterest = devicePoint
            device.exposureMode = .autoExpose
        }
        device.unlockForConfiguration()
    }

    private func setZoom(_ fraction: CGFloat) {
        guard let device else { return }
        let maxZoom = min(device.activeFormat.videoMaxZoomFactor, 6)
        let factor = 1 + fraction * (maxZoom - 1)
        try? device.lockForConfiguration()
        device.videoZoomFactor = max(1, min(factor, maxZoom))
        device.unlockForConfiguration()
    }

    // MARK: - камера

    private func checkPermissionAndStart() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            startSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted { self?.startSession() } else { self?.finish(nil) }
                }
            }
        default:
            finish(nil)
        }
    }

    private func startSession() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.session.beginConfiguration()
            self.session.sessionPreset = .hd1280x720

            guard
                let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                let input = try? AVCaptureDeviceInput(device: device),
                self.session.canAddInput(input)
            else {
                self.session.commitConfiguration()
                DispatchQueue.main.async { self.finish(nil) }
                return
            }
            self.session.addInput(input)
            self.device = device

            self.videoOutput.setSampleBufferDelegate(self, queue: self.sessionQueue)
            self.videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
            self.videoOutput.alwaysDiscardsLateVideoFrames = true
            if self.session.canAddOutput(self.videoOutput) { self.session.addOutput(self.videoOutput) }
            if let connection = self.videoOutput.connection(with: .video) {
                if #available(iOS 17.0, *) {
                    connection.videoRotationAngle = 90
                } else if connection.isVideoOrientationSupported {
                    connection.videoOrientation = .portrait
                }
            }

            self.session.commitConfiguration()
            self.session.startRunning()

            DispatchQueue.main.async {
                let layer = AVCaptureVideoPreviewLayer(session: self.session)
                layer.videoGravity = .resizeAspectFill
                layer.frame = self.view.bounds
                self.view.layer.insertSublayer(layer, at: 0)
                self.previewLayer = layer

                self.torchButton.isHidden = !device.hasTorch
            }
        }
    }

    private func finish(_ value: String?) {
        guard !delivered else { return }
        delivered = true
        sessionQueue.async { [weak self] in self?.session.stopRunning() }
        onFinish?(value)
    }

    deinit {
        session.stopRunning()
    }
}

// MARK: - аналіз кадру

extension QrScannerViewController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard !paused, !delivered, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        frameCount += 1
        // Парні кадри — інвертовані: так ловляться і звичайні коди, і
        // надруковані світлим по чорному (Data Matrix на коробках Starlink).
        let useInverted = frameCount % 2 == 0
        let bufferToScan = useInverted ? invertedLuma(pixelBuffer) : pixelBuffer

        // orientation: .right повертає кадр «як на екрані» для Vision, тож і
        // розміри для порівняння з рамкою мають бути вже розвернуті (сторони
        // поміняні місцями) — так само, як imageW/imageH в Android-версії.
        let rawWidth = CVPixelBufferGetWidth(pixelBuffer)
        let rawHeight = CVPixelBufferGetHeight(pixelBuffer)
        let uprightImageSize = CGSize(width: rawHeight, height: rawWidth)

        let request = VNDetectBarcodesRequest { [weak self] request, _ in
            guard let self else { return }
            guard let results = request.results as? [VNBarcodeObservation], !results.isEmpty else { return }
            self.handle(results: results, imageSize: uprightImageSize)
        }

        let handler = VNImageRequestHandler(cvPixelBuffer: bufferToScan, orientation: .right, options: [:])
        try? handler.perform([request])
    }

    /// Копія кадру з інвертованою яскравістю (лише Y-площина NV12/420f).
    private func invertedLuma(_ pixelBuffer: CVPixelBuffer) -> CVPixelBuffer {
        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }

        guard let yBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) else { return pixelBuffer }
        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
        let stride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)

        let pixelFormatAttrs: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
        ]
        var out: CVPixelBuffer?
        CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange, pixelFormatAttrs as CFDictionary, &out)
        guard let outBuffer = out else { return pixelBuffer }

        CVPixelBufferLockBaseAddress(outBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(outBuffer, []) }

        if let outY = CVPixelBufferGetBaseAddressOfPlane(outBuffer, 0) {
            let outStride = CVPixelBufferGetBytesPerRowOfPlane(outBuffer, 0)
            let src = yBase.assumingMemoryBound(to: UInt8.self)
            let dst = outY.assumingMemoryBound(to: UInt8.self)
            for row in 0..<height {
                let srcRow = src + row * stride
                let dstRow = dst + row * outStride
                for col in 0..<width { dstRow[col] = 255 &- srcRow[col] }
            }
        }
        // Хрому не потрібна для розпізнавання — заповнюємо нейтральним сірим.
        if let outUV = CVPixelBufferGetBaseAddressOfPlane(outBuffer, 1) {
            let uvHeight = CVPixelBufferGetHeightOfPlane(outBuffer, 1)
            let uvStride = CVPixelBufferGetBytesPerRowOfPlane(outBuffer, 1)
            memset(outUV, 128, uvStride * uvHeight)
        }
        return outBuffer
    }

    private func handle(results: [VNBarcodeObservation], imageSize: CGSize) {
        let viewSize = zoneView.bounds.size
        guard let best = results.first(where: { inZone($0, imageSize: imageSize, viewSize: viewSize) }) ?? results.first else { return }
        guard let value = best.payloadStringValue, !value.isEmpty else { return }

        DispatchQueue.main.async { [weak self] in
            self?.propose(value: value, symbology: best.symbology)
        }
    }

    /// Чи потрапив центр коду в рамку. Прев'ю в .resizeAspectFill масштабує
    /// кадр «на заповнення» й обрізає надлишок — повторюємо це перетворення,
    /// щоб перекласти нормалізовані координати Vision (0..1, початок
    /// знизу-зліва) у пікселі екрана, де намальована рамка.
    private func inZone(_ observation: VNBarcodeObservation, imageSize: CGSize, viewSize: CGSize) -> Bool {
        guard imageSize.width > 0, imageSize.height > 0, viewSize.width > 0, viewSize.height > 0 else { return true }

        let box = observation.boundingBox
        let imgX = box.midX * imageSize.width
        let imgY = (1 - box.midY) * imageSize.height

        let scale = max(viewSize.width / imageSize.width, viewSize.height / imageSize.height)
        let dx = (viewSize.width - imageSize.width * scale) / 2
        let dy = (viewSize.height - imageSize.height * scale) / 2

        let point = CGPoint(x: imgX * scale + dx, y: imgY * scale + dy)
        return zoneView.zoneRect().contains(point)
    }

    private func propose(value: String, symbology: VNBarcodeSymbology) {
        guard !paused else { return }
        paused = true
        pendingValue = value
        confirmValueLabel.text = value
        confirmMetaLabel.text = String(format: NSLocalizedString("scan_type", comment: ""), formatName(symbology))
        confirmPanel.isHidden = false

        AudioServicesPlaySystemSound(1057) // короткий тон
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)
    }

    private func formatName(_ symbology: VNBarcodeSymbology) -> String {
        switch symbology {
        case .qr: return "QR"
        case .dataMatrix: return "Data Matrix"
        case .aztec: return "Aztec"
        case .pdf417: return "PDF417"
        case .code128: return "Code 128"
        case .code93: return "Code 93"
        case .code39: return "Code 39"
        case .codabar: return "Codabar"
        case .itf14: return "ITF"
        case .ean13: return "EAN-13"
        case .ean8: return "EAN-8"
        case .upce: return "UPC-E"
        default: return NSLocalizedString("code_generic", comment: "")
        }
    }
}

/// Затемнення поза рамкою + кутики. Рамку можна тягнути пальцем; короткий тап
/// без руху — запит фокуса.
final class ZoneView: UIView {
    var sizeFraction: CGFloat = 0.55 { didSet { setNeedsDisplay() } }
    private var centerXFraction: CGFloat = 0.5
    private var centerYFraction: CGFloat = 0.5

    var onTap: ((CGPoint) -> Void)?
    var onChanged: (() -> Void)?

    private var panStart: CGPoint = .zero
    private var moved = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan))
        addGestureRecognizer(pan)
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap))
        addGestureRecognizer(tap)
    }

    required init?(coder: NSCoder) { fatalError() }

    func zoneRect() -> CGRect {
        let side = min(bounds.width, bounds.height) * sizeFraction
        let half = side / 2
        let cx = min(max(centerXFraction * bounds.width, half), max(half, bounds.width - half))
        let cy = min(max(centerYFraction * bounds.height, half), max(half, bounds.height - half))
        return CGRect(x: cx - half, y: cy - half, width: side, height: side)
    }

    @objc private func handlePan(_ gr: UIPanGestureRecognizer) {
        let point = gr.location(in: self)
        switch gr.state {
        case .began:
            moved = false
            panStart = point
        case .changed:
            let dx = point.x - panStart.x
            let dy = point.y - panStart.y
            if abs(dx) > 3 || abs(dy) > 3 { moved = true }
            guard bounds.width > 0, bounds.height > 0 else { return }
            centerXFraction += dx / bounds.width
            centerYFraction += dy / bounds.height
            panStart = point
            setNeedsDisplay()
            onChanged?()
        default:
            break
        }
    }

    @objc private func handleTap(_ gr: UITapGestureRecognizer) {
        onTap?(gr.location(in: self))
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let box = zoneRect()

        ctx.setFillColor(UIColor(red: 0x0A / 255, green: 0x0E / 255, blue: 0x16 / 255, alpha: 0.5).cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: bounds.width, height: box.minY))
        ctx.fill(CGRect(x: 0, y: box.maxY, width: bounds.width, height: bounds.height - box.maxY))
        ctx.fill(CGRect(x: 0, y: box.minY, width: box.minX, height: box.height))
        ctx.fill(CGRect(x: box.maxX, y: box.minY, width: bounds.width - box.maxX, height: box.height))

        ctx.setStrokeColor(UIColor(red: 0x4C / 255, green: 0x8D / 255, blue: 0xFF / 255, alpha: 1).cgColor)
        ctx.setLineWidth(3)
        ctx.setLineCap(.round)
        let arm = box.width * 0.14
        let corners: [(CGPoint, CGPoint, CGPoint)] = [
            (CGPoint(x: box.minX, y: box.minY + arm), CGPoint(x: box.minX, y: box.minY), CGPoint(x: box.minX + arm, y: box.minY)),
            (CGPoint(x: box.maxX - arm, y: box.minY), CGPoint(x: box.maxX, y: box.minY), CGPoint(x: box.maxX, y: box.minY + arm)),
            (CGPoint(x: box.minX, y: box.maxY - arm), CGPoint(x: box.minX, y: box.maxY), CGPoint(x: box.minX + arm, y: box.maxY)),
            (CGPoint(x: box.maxX - arm, y: box.maxY), CGPoint(x: box.maxX, y: box.maxY), CGPoint(x: box.maxX, y: box.maxY - arm)),
        ]
        for (p1, p2, p3) in corners {
            ctx.move(to: p1); ctx.addLine(to: p2); ctx.addLine(to: p3); ctx.strokePath()
        }
    }
}
