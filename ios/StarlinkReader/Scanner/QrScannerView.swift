import SwiftUI

/// Місток SwiftUI ↔ UIKit для екрана сканування — показується через
/// `.fullScreenCover`, аналог окремої Activity на Android.
struct QrScannerView: UIViewControllerRepresentable {
    let title: String
    let progress: String
    let onFinish: (String?) -> Void

    func makeUIViewController(context: Context) -> QrScannerViewController {
        let vc = QrScannerViewController()
        vc.titleText = title
        vc.progressText = progress
        vc.onFinish = onFinish
        return vc
    }

    func updateUIViewController(_ uiViewController: QrScannerViewController, context: Context) {}
}
