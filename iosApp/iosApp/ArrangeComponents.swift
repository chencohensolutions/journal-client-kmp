import SwiftUI
import SharedLogic

/// Arrange mode of a story's grid: tapping a tile picks it (`StoryTile.key`) to change its size or place.
struct GridArranging {
    let selectedKey: String?
    let select: (String) -> Void
}

/// The picked tile's size presets (Auto first), Earlier / Later among its day's tiles, and Focus; a hint until a
/// tile is picked.
struct ArrangePanel: View {
    let tile: PlacedTile?
    let onSize: (TileSize?) -> Void
    let onMove: (Int32) -> Void
    let onFocus: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            if let tile {
                ScrollView(.horizontal) {
                    HStack(spacing: 4) {
                        ForEach([nil] + TileSize.entries, id: \.self) { size in
                            SizeOption(
                                size: size,
                                shown: size ?? tile.size,
                                photoRatio: tileRatio(tile.tile),
                                selected: size == nil ? !tile.sized : tile.sized && tile.size == size,
                                action: { onSize(size) }
                            )
                        }
                    }
                    .padding(.horizontal, 12)
                }
                .scrollIndicators(.hidden)
                // In reading order, so Earlier is on the right in Hebrew.
                HStack(spacing: 8) {
                    PanelButton(label: "Earlier") { onMove(-1) }
                    PanelButton(label: "Later") { onMove(1) }
                    PanelButton(label: "Focus", action: onFocus)
                }
                .padding(.horizontal, 12)
            } else {
                Text("Tap a photo or group to change its size, place or focus.")
                    .font(.subheadline)
                    .foregroundStyle(LibraryColors.muted)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 28)
            }
        }
        .padding(.vertical, 12)
        .background(LibraryColors.raised.ignoresSafeArea())
    }
}

private struct PanelButton: View {
    let label: LocalizedStringKey
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .foregroundStyle(LibraryColors.onBackground)
                .frame(maxWidth: .infinity, minHeight: 44)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(LibraryColors.onBackground.opacity(0.28)))
        }
    }
}

/// A size preset as its shape on a little 6-column grid, with its name; Auto shows the size it resolves to, Original
/// the photo's own shape.
private struct SizeOption: View {
    let size: TileSize?
    let shown: TileSize
    let photoRatio: CGFloat
    let selected: Bool
    let action: () -> Void

    private let cell: CGFloat = 5

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                let columns = CGFloat(StoryLayoutKt.GRID_COLUMNS)
                let rows = shown == .original ? min(max(columns / photoRatio, 1), 4) : CGFloat(shown.rows)
                // Bottom leading follows the layout direction, so the shapes mirror in Hebrew like the grid.
                ZStack(alignment: .bottomLeading) {
                    RoundedRectangle(cornerRadius: 2).stroke(LibraryColors.muted.opacity(0.5))
                    RoundedRectangle(cornerRadius: 2)
                        .fill(selected ? LibraryColors.warning : LibraryColors.onBackground.opacity(size == nil ? 0.35 : 0.7))
                        .frame(width: cell * CGFloat(shown.columns), height: cell * rows)
                }
                .frame(width: cell * columns, height: cell * 4)
                Text(sizeLabel(size))
                    .font(.system(size: 12, weight: selected ? .bold : .medium))
                    .foregroundStyle(selected ? LibraryColors.onBackground : LibraryColors.muted)
            }
            .frame(width: 64)
            .padding(.vertical, 8)
            .background(selected ? LibraryColors.onBackground.opacity(0.12) : .clear, in: RoundedRectangle(cornerRadius: 12))
        }
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

private func sizeLabel(_ size: TileSize?) -> LocalizedStringKey {
    guard let size else { return "Auto" }
    switch size {
    case TileSize.small: return "Small"
    case TileSize.wide: return "Wide"
    case TileSize.tall: return "Tall"
    case TileSize.half: return "Half"
    case TileSize.large: return "Large"
    case TileSize.full: return "Full"
    default: return "Original"
    }
}

/// The whole photo with the part the tile will show outlined: tap the part that must stay in view, and zoom in up to
/// `MAX_ZOOM`×. The tile as it will look is shown below.
struct FocusEditor: View {
    let tile: PlacedTile
    let onSave: (TileCrop) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var crop: TileCrop
    @State private var image: UIImage?

    init(tile: PlacedTile, onSave: @escaping (TileCrop) -> Void) {
        self.tile = tile
        self.onSave = onSave
        _crop = State(initialValue: TileCrop(tile))
    }

    private var tileAspect: CGFloat { CGFloat(tile.aspectRatio) }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("Cancel") { dismiss() }
                    .foregroundStyle(LibraryColors.onBackground)
                Spacer()
                Text("Focus").font(.headline).foregroundStyle(LibraryColors.onBackground)
                Spacer()
                Button {
                    onSave(crop)
                    dismiss()
                } label: { Text("Save").bold() }
                    .foregroundStyle(LibraryColors.warning)
            }
            .padding(.horizontal, 16)
            .frame(height: 48)
            Text("Tap the part that should stay in view; the frame shows what the tile will show.")
                .font(.subheadline)
                .foregroundStyle(LibraryColors.muted)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
                .padding(.vertical, 8)
            ZStack {
                if let image { photo(image) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(16)
            HStack(spacing: 12) {
                Text("Zoom").font(.subheadline.weight(.semibold)).foregroundStyle(LibraryColors.onBackground)
                Slider(value: $crop.zoom, in: 1...CGFloat(StoryLayoutKt.MAX_ZOOM))
                    .tint(LibraryColors.accent)
                Text(verbatim: String(format: "%.1f×", crop.zoom))
                    .font(.subheadline)
                    .monospacedDigit()
                    .foregroundStyle(LibraryColors.muted)
                    .frame(width: 44)
            }
            .padding(.horizontal, 24)
            if let image {
                TileImage(image: image, size: CGSize(width: 96 * tileAspect, height: 96), crop: crop)
                    .background(LibraryColors.raised)
                    .padding(.top, 4)
                    .padding(.bottom, 20)
            }
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .task(id: tile.tile.key) {
            let item = (tile.tile as? StoryTileGroup)?.group.cover ?? (tile.tile as? StoryTileMedia)?.item
            if let item { image = await PhotoLibraryImages.shared.image(item: item, pointSize: CGSize(width: 800, height: 800)) }
        }
    }

    private func photo(_ image: UIImage) -> some View {
        let photoAspect = image.size.width / max(image.size.height, 1)
        return Image(uiImage: image)
            .resizable()
            .aspectRatio(contentMode: .fit)
            .overlay {
                GeometryReader { geometry in
                    let size = geometry.size
                    // What the tile shows: the largest area of the tile's shape, smaller by the zoom, with the focus
                    // point at the same fraction of it as of the photo.
                    let fitsWidth = photoAspect <= tileAspect
                    let shown = CGSize(
                        width: (fitsWidth ? size.width : size.height * tileAspect) / crop.zoom,
                        height: (fitsWidth ? size.width / tileAspect : size.height) / crop.zoom
                    )
                    let frame = CGRect(
                        origin: CGPoint(x: crop.x * (size.width - shown.width), y: crop.y * (size.height - shown.height)),
                        size: shown
                    )
                    let center = CGPoint(x: crop.x * size.width, y: crop.y * size.height)
                    ZStack(alignment: .topLeading) {
                        Path { path in
                            path.addRect(CGRect(origin: .zero, size: size))
                            path.addRect(frame)
                        }
                        .fill(.black.opacity(0.55), style: FillStyle(eoFill: true))
                        Rectangle().stroke(.white, lineWidth: 2)
                            .frame(width: frame.width, height: frame.height)
                            .offset(x: frame.minX, y: frame.minY)
                        Circle().fill(.white)
                            .frame(width: 6, height: 6)
                            .background(Circle().fill(.black.opacity(0.4)).frame(width: 10, height: 10))
                            .position(center)
                    }
                    .contentShape(Rectangle())
                    .onTapGesture(coordinateSpace: .local) { location in
                        crop.x = min(max(location.x / size.width, 0), 1)
                        crop.y = min(max(location.y / size.height, 0), 1)
                    }
                }
            }
            // The focus point is in the photo's own coordinates, measured from the left: not mirrored in Hebrew.
            .environment(\.layoutDirection, .leftToRight)
            .accessibilityLabel("Focus")
    }
}
