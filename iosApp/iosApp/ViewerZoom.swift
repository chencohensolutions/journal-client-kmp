import SwiftUI
import UIKit
import SharedLogic

// Zoom in the viewer, as Android's ViewerZoom.kt (see docs/screens/viewer.md, Zoom): double-taps step from where the
// photo opens (fitted, or its crop) to filling the screen, to the original's own size, and back; a pinch snaps to one
// of them when let go near it; the minimap shows where the screen is; and the zoom a photo was left at is kept in its
// note and glided back to when it opens again. UIKit's scroll view does the pinching, panning and bouncing.

/// One photo's zoom, for the viewer's overlays and the note: kept while the viewer is open.
@MainActor
final class PhotoZoomModel: ObservableObject {
    /// The part of the photo on screen while zoomed in, as fractions of the upright photo; nil at its home framing.
    @Published private(set) var visible: CGRect?
    /// Away from where the photo opens (fitted, or its crop): one finger moves it, and the viewer doesn't page.
    @Published private(set) var isZoomed = false
    /// Counts each move, so the minimap can show while the photo moves and a moment after.
    @Published private(set) var moves = 0
    /// The original decoded at full size is on screen: the minimap is grey until it is.
    @Published var atOriginalQuality = false
    /// The smallest picture of the whole photo its page has ready, for the minimap.
    @Published var picture: UIImage?

    /// Where it was left last time, until `resume` glides there.
    private var saved: CropRect?
    private var resumeAsked = false
    weak var controller: ZoomController?

    init(saved: CropRect?) {
        self.saved = saved
    }

    var hasSaved: Bool { saved != nil }

    /// Glides to where it was left last time, once its page is laid out.
    func resume() {
        guard let part = saved else { return }
        guard let controller, controller.isLaidOut else {
            resumeAsked = true
            return
        }
        saved = nil
        controller.glide(to: part)
    }

    /// Its page is laid out: a resume asked for before can go now.
    func laidOut() {
        if resumeAsked {
            resumeAsked = false
            resume()
        }
    }

    /// What to keep in the photo's note when it is left: the part on screen with the screen's shape, or nil at home.
    var zoomedPart: CropRect? {
        if let saved { return saved }
        guard isZoomed, let visible, let controller else { return nil }
        return CropRect(
            x: Float(visible.minX), y: Float(visible.minY), w: Float(visible.width), h: Float(visible.height),
            aspect: KotlinFloat(float: Float(controller.screenRatio))
        )
    }

    fileprivate func update(visible: CGRect?, zoomed: Bool) {
        self.visible = zoomed ? visible : nil
        if isZoomed != zoomed { isZoomed = zoomed }
        moves &+= 1
    }
}

/// The models of the photos seen while the viewer is open, by item id.
@MainActor
final class ZoomModels: ObservableObject {
    private var models: [String: PhotoZoomModel] = [:]

    func model(for itemId: String, saved: CropRect?) -> PhotoZoomModel {
        if let model = models[itemId] { return model }
        let model = PhotoZoomModel(saved: saved)
        models[itemId] = model
        return model
    }

    func existing(_ itemId: String) -> PhotoZoomModel? { models[itemId] }
}

/// A pinch let go within this factor of a step goes to it.
private let snapFactor: CGFloat = 1.15
private let glideSeconds = 0.55

/// The UIKit side: a scroll view zooming an image view that holds the photo fitted in it (zoom scale 1).
@MainActor
final class ZoomController: NSObject, UIScrollViewDelegate {
    let scrollView = LayoutReportingScrollView()
    private let imageView = UIImageView()
    weak var model: PhotoZoomModel?
    var onTap: () -> Void = {}
    /// The longer side of the photo's own pixels (the original's, not the decoded image's).
    var pixelSide: CGFloat = 0
    /// Where the photo opens: its crop (fractions of the photo), else fitted.
    private var home: CropRect?
    private var laidOutSize = CGSize.zero
    private var fitted = CGSize.zero

    override init() {
        super.init()
        scrollView.delegate = self
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        scrollView.contentInsetAdjustmentBehavior = .never
        scrollView.bouncesZoom = true
        scrollView.minimumZoomScale = 1
        scrollView.backgroundColor = .clear
        imageView.contentMode = .scaleToFill
        scrollView.addSubview(imageView)
        scrollView.onLayout = { [weak self] in self?.layout() }

        let double = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
        double.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(double)
        let single = UITapGestureRecognizer(target: self, action: #selector(tapped))
        single.require(toFail: double)
        scrollView.addGestureRecognizer(single)
    }

    var isLaidOut: Bool { !fitted.equalTo(.zero) }
    var screenRatio: CGFloat { scrollView.bounds.width / max(scrollView.bounds.height, 1) }

    func setImage(_ image: UIImage) {
        guard imageView.image !== image else { return }
        let first = imageView.image == nil
        let ratioChanged = imageView.image.map { abs(ratio(of: $0) - ratio(of: image)) > 0.01 } ?? true
        if first || ratioChanged {
            imageView.image = image
            laidOutSize = .zero
            scrollView.setNeedsLayout()
        } else {
            // A sharper image of the same photo: the same place, faded in.
            UIView.transition(with: imageView, duration: 0.25, options: .transitionCrossDissolve) { self.imageView.image = image }
        }
    }

    func setHome(_ home: CropRect?) {
        guard !Self.same(home, self.home) else { return }
        let wasHome = !isZoomedNow
        self.home = home
        guard isLaidOut, wasHome else { return }
        goHome(animated: true)
    }

    private func ratio(of image: UIImage) -> CGFloat { image.size.width / max(image.size.height, 1) }

    private func layout() {
        let bounds = scrollView.bounds.size
        guard bounds.width > 0, bounds.height > 0, let image = imageView.image, bounds != laidOutSize else { return }
        laidOutSize = bounds
        let photoRatio = ratio(of: image)
        fitted = bounds.width / bounds.height > photoRatio
            ? CGSize(width: bounds.height * photoRatio, height: bounds.height)
            : CGSize(width: bounds.width, height: bounds.width / photoRatio)
        scrollView.zoomScale = 1
        imageView.frame = CGRect(origin: .zero, size: fitted)
        scrollView.contentSize = fitted
        scrollView.maximumZoomScale = maxScale
        goHome(animated: false)
        report()
        model?.laidOut()
    }

    // MARK: Steps

    private var fillScale: CGFloat {
        guard isLaidOut else { return 1 }
        let bounds = scrollView.bounds.size
        return max(bounds.width / fitted.width, bounds.height / fitted.height)
    }

    /// One photo pixel on one screen pixel.
    private var originalScale: CGFloat {
        guard isLaidOut, pixelSide > 0 else { return 1 }
        return pixelSide / (max(fitted.width, fitted.height) * scrollView.traitCollection.displayScale)
    }

    /// At most twice the original's own size, and never less than filling the screen.
    private var maxScale: CGFloat { max(2 * originalScale, fillScale, 1) }

    private var homeScale: CGFloat { home.map(scaleShowing) ?? 1 }

    /// Home, filling the screen, and the original's size, without the ones that add nothing.
    private var steps: [CGFloat] {
        let home = homeScale
        let candidates = ([home, fillScale, originalScale].filter { $0 >= home && $0 <= maxScale }).sorted()
        return candidates.reduce(into: [CGFloat]()) { kept, step in
            if let last = kept.last, step < last * 1.05 { return }
            kept.append(step)
        }
    }

    private var isZoomedNow: Bool {
        isLaidOut && abs(scrollView.zoomScale - homeScale) / homeScale > 0.01
    }

    /// The zoom at which `part` fills the screen as far as it can.
    private func scaleShowing(_ part: CropRect) -> CGFloat {
        let bounds = scrollView.bounds.size
        let scale = min(bounds.width / (CGFloat(part.w) * fitted.width), bounds.height / (CGFloat(part.h) * fitted.height))
        return min(max(scale, 1), maxScale)
    }

    /// `part` (fractions of the photo) in the image view's own coordinates.
    private func rect(_ part: CropRect) -> CGRect {
        CGRect(x: CGFloat(part.x) * fitted.width, y: CGFloat(part.y) * fitted.height, width: CGFloat(part.w) * fitted.width, height: CGFloat(part.h) * fitted.height)
    }

    /// The screen's worth of photo at `scale`, centred on `point` (image view coordinates).
    private func rect(scale: CGFloat, around point: CGPoint) -> CGRect {
        let size = CGSize(width: scrollView.bounds.width / scale, height: scrollView.bounds.height / scale)
        return CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height)
    }

    private func goHome(animated: Bool) {
        if let home {
            let scale = scaleShowing(home)
            let middle = CGPoint(x: rect(home).midX, y: rect(home).midY)
            scrollView.zoom(to: rect(scale: scale, around: middle), animated: animated)
        } else {
            scrollView.setZoomScale(1, animated: animated)
        }
    }

    @objc private func tapped() { onTap() }

    @objc private func doubleTapped(_ gesture: UITapGestureRecognizer) {
        let point = gesture.location(in: imageView)
        if let next = steps.first(where: { $0 > scrollView.zoomScale * 1.02 }) {
            scrollView.zoom(to: rect(scale: next, around: point), animated: true)
        } else {
            goHome(animated: true)
        }
    }

    /// Glides from what is on screen to `part`: one camera move, the middle sliding as the scale changes.
    func glide(to part: CropRect) {
        guard isLaidOut else { return }
        let scale = scaleShowing(part)
        let target = rect(scale: scale, around: CGPoint(x: rect(part).midX, y: rect(part).midY))
        UIView.animate(withDuration: glideSeconds, delay: 0, options: [.curveEaseInOut, .allowUserInteraction]) {
            self.scrollView.zoom(to: target, animated: false)
        }
    }

    // MARK: UIScrollViewDelegate

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        center()
        report()
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) { report() }

    func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
        // Pinched out past home: back to it. Near a step: to it, around the middle of the screen.
        if scale < homeScale * 0.99 {
            goHome(animated: true)
            return
        }
        guard let near = steps.min(by: { abs(log(scale / $0)) < abs(log(scale / $1)) }),
              abs(log(scale / near)) < log(snapFactor), abs(scale - near) > 0.001
        else { return }
        let middle = scrollView.convert(CGPoint(x: scrollView.bounds.midX, y: scrollView.bounds.midY), to: imageView)
        if abs(near - homeScale) < 0.001 {
            goHome(animated: true)
        } else {
            scrollView.zoom(to: rect(scale: near, around: middle), animated: true)
        }
    }

    /// A side smaller than the screen stays centred.
    private func center() {
        let bounds = scrollView.bounds.size
        let content = scrollView.contentSize
        let x = max(0, (bounds.width - content.width) / 2)
        let y = max(0, (bounds.height - content.height) / 2)
        scrollView.contentInset = UIEdgeInsets(top: y, left: x, bottom: y, right: x)
    }

    private func report() {
        guard isLaidOut else { return }
        center()
        let zoomed = isZoomedNow
        // At home, a pan swipes to the next photo; zoomed in, it moves this one.
        scrollView.isScrollEnabled = zoomed
        let shown = scrollView.convert(scrollView.bounds, to: imageView)
        let part = CGRect(x: shown.minX / fitted.width, y: shown.minY / fitted.height, width: shown.width / fitted.width, height: shown.height / fitted.height)
            .intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
        model?.update(visible: part.isNull ? nil : part, zoomed: zoomed)
    }

    private static func same(_ a: CropRect?, _ b: CropRect?) -> Bool {
        guard let a, let b else { return a == nil && b == nil }
        return abs(a.x - b.x) < 0.001 && abs(a.y - b.y) < 0.001 && abs(a.w - b.w) < 0.001 && abs(a.h - b.h) < 0.001
    }
}

/// A scroll view that says when it is laid out, so the photo is fitted to its size.
final class LayoutReportingScrollView: UIScrollView {
    var onLayout: (() -> Void)?

    override func layoutSubviews() {
        super.layoutSubviews()
        onLayout?()
    }
}

/// `image` zoomable as `model` keeps it; `home` is where it opens (its crop), `pixelSide` the original's longer side.
struct ZoomablePhoto: UIViewRepresentable {
    let image: UIImage
    @ObservedObject var model: PhotoZoomModel
    let pixelSide: CGFloat
    let home: CropRect?
    let onTap: () -> Void

    func makeCoordinator() -> ZoomController { ZoomController() }

    func makeUIView(context: Context) -> UIScrollView {
        let controller = context.coordinator
        controller.model = model
        model.controller = controller
        return controller.scrollView
    }

    func updateUIView(_ view: UIScrollView, context: Context) {
        let controller = context.coordinator
        controller.onTap = onTap
        controller.pixelSide = pixelSide
        controller.setImage(image)
        controller.setHome(home)
    }
}

/// Most pixels of an original decoded for zooming: a 48 MP photo whole, larger ones scaled to that.
private let zoomMaxPixels: Double = 48_000_000
/// The largest texture iPhones draw.
private let zoomMaxSide = 16_384

/// The longer side `item`'s original is decoded at for zooming: its own, unless that is too many pixels to draw.
func zoomDecodeSide(_ item: MediaItem) -> Int {
    let width = Double(item.width), height = Double(item.height)
    let longer = max(width, height)
    guard width > 0, height > 0 else { return 8192 }
    let side = width * height <= zoomMaxPixels ? longer : longer * (zoomMaxPixels / (width * height)).squareRoot()
    return min(Int(side), zoomMaxSide)
}

/// Where the screen is on the whole photo while zoomed: `image` small with the part on screen (`part`, 0..1)
/// outlined and the rest dimmed; grey until the photo on screen is at the original's quality (`sharp`).
struct ZoomMinimap: View {
    let image: UIImage
    let part: CGRect
    let sharp: Bool
    var side: CGFloat = 96

    var body: some View {
        let ratio = image.size.width / max(image.size.height, 1)
        let size = ratio >= 1 ? CGSize(width: side, height: side / ratio) : CGSize(width: side * ratio, height: side)
        Image(uiImage: image)
            .resizable()
            .grayscale(sharp ? 0 : 1)
            .animation(.easeInOut(duration: 0.3), value: sharp)
            .frame(width: size.width, height: size.height)
            .overlay {
                Canvas { context, canvas in
                    let shown = CGRect(
                        x: part.minX * canvas.width, y: part.minY * canvas.height,
                        width: part.width * canvas.width, height: part.height * canvas.height
                    )
                    var rest = Path(CGRect(origin: .zero, size: canvas))
                    rest.addRect(shown)
                    context.fill(rest, with: .color(.black.opacity(0.5)), style: FillStyle(eoFill: true))
                    context.stroke(Path(shown), with: .color(.white), lineWidth: 1.5)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .shadow(color: .black.opacity(0.4), radius: 6)
            // The part is in the photo's coordinates, which don't mirror in Hebrew.
            .environment(\.layoutDirection, .leftToRight)
            .accessibilityHidden(true)
    }
}

/// The original coming from the server: a cloud outline filling from the bottom as `progress` (0..1) goes.
struct DownloadCloud: View {
    let progress: Double

    var body: some View {
        ZStack {
            Image(systemName: "cloud.fill")
                .font(.system(size: 17))
                .foregroundStyle(.white)
                .mask(alignment: .bottom) {
                    GeometryReader { geometry in
                        Rectangle()
                            .frame(height: geometry.size.height * progress)
                            .frame(maxHeight: .infinity, alignment: .bottom)
                    }
                }
            Image(systemName: "cloud").font(.system(size: 17)).foregroundStyle(.white)
        }
        .animation(.easeOut(duration: 0.2), value: progress)
        .frame(width: 34, height: 34)
        .background(LibraryColors.pillScrim, in: Circle())
        .accessibilityElement()
        .accessibilityLabel("Downloading the original")
        .accessibilityValue("\(Int(progress * 100))%")
    }
}
