import AVFoundation
import CoreMedia
import ImageIO
import Photos
import SharedLogic

// What the viewer's details card shows, read from the original file on this phone (read only, off the main thread):
// a photo's EXIF through ImageIO, a video's tracks and metadata through AVFoundation. Like Android's DetailsCard
// (InfoPanelSuggestions.kt).

/// One section of "All EXIF" / "All video details": its name and label–value rows.
struct DetailSection: Identifiable {
    let name: String
    let rows: [(label: String, value: String)]
    var id: String { name }
}

/// The card's content: a title ("Google Pixel 8 Pro", "HEVC · 4K · 60 fps"), a line under it, the usual facts as
/// chips, and every tag by section behind a link.
struct OriginalDetails {
    var title: String?
    var subtitle: String?
    var chips: [String]
    var sections: [DetailSection]

    var rowCount: Int { sections.reduce(0) { $0 + $1.rows.count } }
}

/// Where the original's bytes come from, as far as this phone has them.
enum OriginalSource {
    case photoData(Data)
    case photoFile(URL)
    case video(AVAsset)
}

enum OriginalDetailsReader {
    /// The original of `item` on this phone: from PhotoKit for a library item (an iCloud one is fetched by PhotoKit),
    /// else its kept original or the one downloaded into the cache. A server-only video is read streamed, as it plays
    /// (only its header is fetched). Nil while a server photo's original isn't here yet.
    @MainActor
    static func source(_ item: MediaItem) async -> OriginalSource? {
        if item.isOnDevice {
            if item.isVideo {
                return await PhotoLibraryImages.shared.videoAsset(id: item.id).map { .video($0) }
            }
            return await photoData(id: item.id).map { .photoData($0) }
        }
        if let path = await ServerImages.shared.originalPath(item) {
            let url = URL(fileURLWithPath: path)
            return item.isVideo ? .video(AVURLAsset(url: url)) : .photoFile(url)
        }
        if item.isVideo, let asset = ServerVideos.asset(item) { return .video(asset) }
        return nil
    }

    /// Reads `source`, off the main thread.
    static func read(_ source: OriginalSource, item: MediaItem) async -> OriginalDetails? {
        switch source {
        case .photoData(let data):
            return await Task.detached(priority: .utility) {
                CGImageSourceCreateWithData(data as CFData, nil).flatMap(photoDetails)
            }.value
        case .photoFile(let url):
            return await Task.detached(priority: .utility) {
                CGImageSourceCreateWithURL(url as CFURL, nil).flatMap(photoDetails)
            }.value
        case .video(let asset):
            return await Task.detached(priority: .utility) { await videoDetails(asset, fileBytes: item.sizeBytes?.int64Value) }.value
        }
    }

    /// The library photo's original bytes, unedited (where the camera's EXIF is).
    private static func photoData(id: String) async -> Data? {
        guard let asset = PhotoLibraryImages.shared.asset(id) else { return nil }
        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .highQualityFormat
        options.version = .original
        return await withCheckedContinuation { continuation in
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, _ in
                continuation.resume(returning: data)
            }
        }
    }
}

// MARK: Photos

private let exifKey = kCGImagePropertyExifDictionary as String
private let tiffKey = kCGImagePropertyTIFFDictionary as String
private let gpsKey = kCGImagePropertyGPSDictionary as String
private let auxKey = kCGImagePropertyExifAuxDictionary as String

private func photoDetails(_ source: CGImageSource) -> OriginalDetails? {
    guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any] else { return nil }
    let exif = properties[exifKey] as? [String: Any] ?? [:]
    let tiff = properties[tiffKey] as? [String: Any] ?? [:]
    let aux = properties[auxKey] as? [String: Any] ?? [:]

    let make = (tiff["Make"] as? String)?.cleaned
    let model = (tiff["Model"] as? String)?.cleaned
    var title: String?
    if let make, let model {
        title = model.lowercased().hasPrefix(make.lowercased()) ? model : "\(make) \(model)"
    } else {
        title = model ?? make
    }
    let lens = (exif["LensModel"] as? String ?? aux["LensModel"] as? String)?.cleaned

    var chips: [String] = []
    if let iso = firstNumber(exif["ISOSpeedRatings"]) { chips.append("ISO \(plain(iso))") }
    if let focal = number(exif["FocalLenIn35mmFilm"]), focal > 0 {
        chips.append("\(plain(focal)) mm")
    } else if let focal = number(exif["FocalLength"]) {
        chips.append("\(plain(focal)) mm")
    }
    if let f = number(exif["FNumber"]) { chips.append(fNumber(f)) }
    if let time = number(exif["ExposureTime"]) { chips.append(exposureTime(time)) }
    if let bias = number(exif["ExposureBiasValue"]) { chips.append("\(plain(bias)) EV") }

    var sections: [DetailSection] = []
    let image = properties.filter { !($0.value is [String: Any]) }
    if let rows = photoRows(image, table: imageTags), !rows.isEmpty {
        sections.append(DetailSection(name: String(localized: "Image"), rows: rows))
    }
    let dictionaries = properties.compactMap { key, value in (value as? [String: Any]).map { (key, $0) } }
    for (key, dictionary) in dictionaries.sorted(by: { sectionOrder($0.0) < sectionOrder($1.0) }) {
        let table: [String: PhotoTag]
        switch key {
        case exifKey: table = exifTags
        case tiffKey: table = tiffTags
        case gpsKey: table = [:]
        case auxKey: table = auxTags
        default: table = [:]
        }
        let rows = key == gpsKey ? gpsRows(dictionary) : photoRows(dictionary, table: table)
        if let rows, !rows.isEmpty { sections.append(DetailSection(name: sectionName(key), rows: rows)) }
    }
    return OriginalDetails(title: title, subtitle: lens, chips: chips, sections: sections)
}

/// A well-known tag: its label and how its value is written.
private struct PhotoTag {
    let order: Int
    let label: String
    let format: ((Any) -> String?)?

    init(_ order: Int, _ label: String, _ format: ((Any) -> String?)? = nil) {
        self.order = order
        self.label = label
        self.format = format
    }
}

private let imageTags: [String: PhotoTag] = [
    "PixelWidth": PhotoTag(0, String(localized: "Width"), { number($0).map { "\(plain($0)) px" } }),
    "PixelHeight": PhotoTag(1, String(localized: "Height"), { number($0).map { "\(plain($0)) px" } }),
    "Orientation": PhotoTag(2, String(localized: "Orientation"), { number($0).map(orientation) }),
    "ProfileName": PhotoTag(3, String(localized: "Color profile")),
    "ColorModel": PhotoTag(4, String(localized: "Color model")),
    "Depth": PhotoTag(5, String(localized: "Bit depth"), { number($0).map { "\(plain($0))-bit" } }),
    "DPIWidth": PhotoTag(6, String(localized: "Resolution"), { number($0).map { "\(plain($0)) dpi" } }),
    "DPIHeight": PhotoTag(7, String(localized: "Vertical resolution"), { number($0).map { "\(plain($0)) dpi" } }),
    "HasAlpha": PhotoTag(8, String(localized: "Transparency"), { number($0).map { $0 != 0 ? String(localized: "Yes") : String(localized: "No") } }),
]

private let tiffTags: [String: PhotoTag] = [
    "Make": PhotoTag(0, String(localized: "Make")),
    "Model": PhotoTag(1, String(localized: "Model")),
    "Software": PhotoTag(2, String(localized: "Software")),
    "HostComputer": PhotoTag(3, String(localized: "Device")),
    "DateTime": PhotoTag(4, String(localized: "Modified")),
    "Orientation": PhotoTag(5, String(localized: "Orientation"), { number($0).map(orientation) }),
    "XResolution": PhotoTag(6, String(localized: "Resolution"), { number($0).map { "\(plain($0)) dpi" } }),
    "YResolution": PhotoTag(7, String(localized: "Vertical resolution"), { number($0).map { "\(plain($0)) dpi" } }),
]

private let exifTags: [String: PhotoTag] = [
    "LensMake": PhotoTag(0, String(localized: "Lens make")),
    "LensModel": PhotoTag(1, String(localized: "Lens")),
    "ExposureTime": PhotoTag(2, String(localized: "Exposure time"), { number($0).map(exposureTime) }),
    "FNumber": PhotoTag(3, String(localized: "F-number"), { number($0).map(fNumber) }),
    "ISOSpeedRatings": PhotoTag(4, "ISO", { firstNumber($0).map(plain) }),
    "FocalLength": PhotoTag(5, String(localized: "Focal length"), { number($0).map { "\(plain($0)) mm" } }),
    "FocalLenIn35mmFilm": PhotoTag(6, String(localized: "Focal length (35 mm)"), { number($0).map { "\(plain($0)) mm" } }),
    "ExposureBiasValue": PhotoTag(7, String(localized: "Exposure bias"), { number($0).map { "\(plain($0)) EV" } }),
    "ExposureProgram": PhotoTag(8, String(localized: "Exposure program"), { number($0).map(exposureProgram) }),
    "ExposureMode": PhotoTag(9, String(localized: "Exposure mode"), { number($0).map(exposureMode) }),
    "MeteringMode": PhotoTag(10, String(localized: "Metering mode"), { number($0).map(meteringMode) }),
    "Flash": PhotoTag(11, String(localized: "Flash"), { number($0).map(flash) }),
    "WhiteBalance": PhotoTag(12, String(localized: "White balance"), { number($0).map { $0 == 0 ? String(localized: "Auto") : String(localized: "Manual") } }),
    "SceneCaptureType": PhotoTag(13, String(localized: "Scene type"), { number($0).map(sceneType) }),
    "ApertureValue": PhotoTag(14, String(localized: "Aperture value"), { number($0).map { "\(plain($0)) EV" } }),
    "ShutterSpeedValue": PhotoTag(15, String(localized: "Shutter speed value"), { number($0).map { "\(plain($0)) EV" } }),
    "BrightnessValue": PhotoTag(16, String(localized: "Brightness value"), { number($0).map { "\(plain($0)) EV" } }),
    "DateTimeOriginal": PhotoTag(17, String(localized: "Taken")),
    "DateTimeDigitized": PhotoTag(18, String(localized: "Digitized")),
    "OffsetTimeOriginal": PhotoTag(19, String(localized: "Time zone")),
    "PixelXDimension": PhotoTag(20, String(localized: "Width"), { number($0).map { "\(plain($0)) px" } }),
    "PixelYDimension": PhotoTag(21, String(localized: "Height"), { number($0).map { "\(plain($0)) px" } }),
    "ColorSpace": PhotoTag(22, String(localized: "Color space"), { number($0).map { $0 == 1 ? "sRGB" : String(localized: "Uncalibrated") } }),
]

private let auxTags: [String: PhotoTag] = [
    "LensModel": PhotoTag(0, String(localized: "Lens")),
    "LensID": PhotoTag(1, String(localized: "Lens ID")),
    "SerialNumber": PhotoTag(2, String(localized: "Serial number")),
]

/// The rows of one dictionary: well-known tags first, in their order and with their labels, then the rest by name.
private func photoRows(_ dictionary: [String: Any], table: [String: PhotoTag]) -> [(label: String, value: String)]? {
    var known: [(Int, String, String)] = []
    var rest: [(String, String)] = []
    for (key, raw) in dictionary {
        if let tag = table[key] {
            if let value = tag.format?(raw) ?? plainValue(raw) { known.append((tag.order, tag.label, value)) }
        } else if let value = plainValue(raw) {
            rest.append((readable(key), value))
        }
    }
    return known.sorted { $0.0 < $1.0 }.map { ($0.1, $0.2) } + rest.sorted { $0.0 < $1.0 }.map { ($0.0, $0.1) }
}

/// GPS with its references folded in: "32.81431° N", "212 m above sea level".
private func gpsRows(_ gps: [String: Any]) -> [(label: String, value: String)] {
    var rows: [(label: String, value: String)] = []
    var used: Set<String> = []
    if let latitude = number(gps["Latitude"]) {
        rows.append((String(localized: "Latitude"), String(format: "%.5f° %@", locale: Locale(identifier: "en_US_POSIX"), latitude, gps["LatitudeRef"] as? String ?? "N")))
        used.formUnion(["Latitude", "LatitudeRef"])
    }
    if let longitude = number(gps["Longitude"]) {
        rows.append((String(localized: "Longitude"), String(format: "%.5f° %@", locale: Locale(identifier: "en_US_POSIX"), longitude, gps["LongitudeRef"] as? String ?? "E")))
        used.formUnion(["Longitude", "LongitudeRef"])
    }
    if let altitude = number(gps["Altitude"]) {
        let below = number(gps["AltitudeRef"]) == 1
        let meters = Int(altitude.rounded())
        rows.append((String(localized: "Altitude"), below ? String(localized: "\(meters) m below sea level") : String(localized: "\(meters) m above sea level")))
        used.formUnion(["Altitude", "AltitudeRef"])
    }
    if let direction = number(gps["ImgDirection"]) {
        rows.append((String(localized: "Direction"), "\(Int(direction.rounded()))°"))
        used.formUnion(["ImgDirection", "ImgDirectionRef"])
    }
    if let speed = number(gps["Speed"]) {
        let unit = (gps["SpeedRef"] as? String).map { ["K": "km/h", "M": "mph", "N": "knots"][$0] ?? $0 } ?? "km/h"
        rows.append((String(localized: "Speed"), "\(plain(speed)) \(unit)"))
        used.formUnion(["Speed", "SpeedRef"])
    }
    if let date = gps["DateStamp"] as? String {
        let time = gps["TimeStamp"] as? String
        rows.append((String(localized: "Time (UTC)"), [date, time].compactMap { $0 }.joined(separator: " ")))
        used.formUnion(["DateStamp", "TimeStamp"])
    }
    if let error = number(gps["HPositioningError"]) {
        rows.append((String(localized: "Accuracy"), "\(plain(error)) m"))
        used.insert("HPositioningError")
    }
    let rest = gps.filter { !used.contains($0.key) }.compactMap { key, raw in plainValue(raw).map { (readable(key), $0) } }
    return rows + rest.sorted { $0.0 < $1.0 }.map { (label: $0.0, value: $0.1) }
}

private func sectionOrder(_ key: String) -> Int {
    switch key {
    case tiffKey: return 0
    case exifKey: return 1
    case auxKey: return 2
    case gpsKey: return 3
    default: return 4
    }
}

/// "{Exif}" → "EXIF", "{MakerApple}" → "Apple", "{HEIF}" → "HEIF".
private func sectionName(_ key: String) -> String {
    let name = key.trimmingCharacters(in: CharacterSet(charactersIn: "{}"))
    switch name {
    case "Exif": return "EXIF"
    case "ExifAux": return String(localized: "EXIF, more")
    case "TIFF", "GPS", "IPTC", "JFIF", "PNG", "GIF", "HEIF", "DNG", "CIFF", "8BIM", "TGA", "WebP", "AVIS": return name
    default:
        if name.hasPrefix("Maker") { return String(localized: "Maker notes (\(String(name.dropFirst(5))))") }
        return readable(name)
    }
}

/// A value written as it is: text, a number, or a short list of them; nil for data, dictionaries and long lists.
private func plainValue(_ raw: Any) -> String? {
    switch raw {
    case let text as String:
        let trimmed = text.cleaned
        return trimmed.isEmpty ? nil : trimmed
    case let number as NSNumber:
        return plain(number.doubleValue)
    case let list as [Any]:
        guard !list.isEmpty, list.count <= 4 else { return nil }
        let values = list.compactMap { $0 is [Any] || $0 is [String: Any] ? nil : plainValue($0) }
        return values.count == list.count ? values.joined(separator: ", ") : nil
    default:
        return nil
    }
}

/// "ExposureTime" → "Exposure time", "ISOSpeedRatings" → "ISO speed ratings".
private func readable(_ key: String) -> String {
    var words: [String] = []
    var current = ""
    let chars = Array(key)
    for (i, c) in chars.enumerated() {
        let next = i + 1 < chars.count ? chars[i + 1] : nil
        let boundary = !current.isEmpty && (
            (c.isUppercase && (current.last?.isLowercase == true || current.last?.isNumber == true))
                || (c.isUppercase && next?.isLowercase == true && current.last?.isUppercase == true)
                || (c.isNumber && current.last?.isLetter == true)
        )
        if boundary { words.append(current); current = "" }
        if c != "_" { current.append(c) } else if !current.isEmpty { words.append(current); current = "" }
    }
    if !current.isEmpty { words.append(current) }
    return words.enumerated().map { i, word in
        let acronym = word.count > 1 && word.allSatisfy { $0.isUppercase || $0.isNumber }
        if acronym { return word }
        return i == 0 ? word.prefix(1).uppercased() + word.dropFirst() : word.lowercased()
    }.joined(separator: " ")
}

private func number(_ raw: Any?) -> Double? { (raw as? NSNumber)?.doubleValue }

private func firstNumber(_ raw: Any?) -> Double? { number(raw) ?? (raw as? [Any])?.first.flatMap(number) }

/// 50 → "50", 1.68 → "1.68", 6.8999 → "6.9".
func plain(_ value: Double) -> String {
    if value.rounded() == value, abs(value) < 1e12 { return String(Int64(value)) }
    let formatter = NumberFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.maximumFractionDigits = 2
    formatter.minimumFractionDigits = 0
    return formatter.string(from: NSNumber(value: value)) ?? String(value)
}

private func fNumber(_ value: Double) -> String { "f/\(plain(value))" }

/// 0.004 → "1/250 s", 2.5 → "2.5 s".
private func exposureTime(_ seconds: Double) -> String {
    if seconds > 0, seconds < 1 { return "1/\(Int((1 / seconds).rounded())) s" }
    return "\(plain(seconds)) s"
}

private func orientation(_ value: Double) -> String {
    switch Int(value) {
    case 1: return String(localized: "Normal")
    case 3: return String(localized: "Rotated 180°")
    case 6: return String(localized: "Rotated 90° clockwise")
    case 8: return String(localized: "Rotated 90° counterclockwise")
    default: return String(localized: "Mirrored")
    }
}

private func exposureProgram(_ value: Double) -> String {
    switch Int(value) {
    case 1: return String(localized: "Manual")
    case 2: return String(localized: "Normal program")
    case 3: return String(localized: "Aperture priority")
    case 4: return String(localized: "Shutter priority")
    case 7: return String(localized: "Portrait mode")
    case 8: return String(localized: "Landscape mode")
    default: return plain(value)
    }
}

private func exposureMode(_ value: Double) -> String {
    switch Int(value) {
    case 0: return String(localized: "Auto")
    case 1: return String(localized: "Manual")
    case 2: return String(localized: "Auto bracket")
    default: return plain(value)
    }
}

private func meteringMode(_ value: Double) -> String {
    switch Int(value) {
    case 1: return String(localized: "Average")
    case 2: return String(localized: "Center-weighted average")
    case 3: return String(localized: "Spot")
    case 4: return String(localized: "Multi-spot")
    case 5: return String(localized: "Pattern")
    case 6: return String(localized: "Partial")
    default: return plain(value)
    }
}

private func flash(_ value: Double) -> String {
    Int(value) & 1 == 1 ? String(localized: "Fired") : String(localized: "Did not fire")
}

private func sceneType(_ value: Double) -> String {
    switch Int(value) {
    case 0: return String(localized: "Standard")
    case 1: return String(localized: "scene.landscape", defaultValue: "Landscape")
    case 2: return String(localized: "scene.portrait", defaultValue: "Portrait")
    case 3: return String(localized: "Night")
    default: return plain(value)
    }
}

private extension String {
    var cleaned: String { trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "\0"))) }
}

// MARK: Videos

private func videoDetails(_ asset: AVAsset, fileBytes: Int64?) async -> OriginalDetails? {
    guard let tracks = try? await asset.load(.tracks) else { return nil }
    let duration = (try? await asset.load(.duration)).map(CMTimeGetSeconds) ?? 0
    var videoRows: [(label: String, value: String)] = []
    var audioRows: [(label: String, value: String)] = []
    var fileRows: [(label: String, value: String)] = []
    var title: [String] = []
    var subtitle: [String] = []
    var chips: [String] = []

    if let video = tracks.first(where: { $0.mediaType == .video }) {
        let description = (try? await video.load(.formatDescriptions))?.first
        let size = (try? await video.load(.naturalSize)) ?? .zero
        let rate = (try? await video.load(.nominalFrameRate)) ?? 0
        let bitrate = (try? await video.load(.estimatedDataRate)) ?? 0
        let transform = (try? await video.load(.preferredTransform)) ?? .identity
        let width = Int(size.width), height = Int(size.height)
        var codec: String?
        var profile: String?
        var transfer: String?
        var bits: Int?
        if let description {
            let fourCC = CMFormatDescriptionGetMediaSubType(description)
            codec = videoCodec(fourCC)
            profile = videoProfile(description, fourCC: fourCC)
            transfer = (CMFormatDescriptionGetExtension(description, extensionKey: kCMFormatDescriptionExtension_TransferFunction) as? String)
                .flatMap(transferName)
            bits = (CMFormatDescriptionGetExtension(description, extensionKey: kCMFormatDescriptionExtension_BitsPerComponent) as? NSNumber)?.intValue
            if bits == nil, profile?.contains("10") == true { bits = 10 }
            videoRows.append((String(localized: "Codec"), [codecLongName(fourCC) ?? codec, profile.map { String(localized: "\($0) profile") }].compactMap { $0 }.joined(separator: ", ")))
        }
        if width > 0 && height > 0 {
            let name = resolutionName(width, height)
            videoRows.append((String(localized: "Resolution"), name.map { "\(width) × \(height) (\($0))" } ?? "\(width) × \(height)"))
        }
        if rate > 0 { videoRows.append((String(localized: "Frame rate"), "\(plain(Double(rate))) fps")) }
        if bitrate > 0 { videoRows.append((String(localized: "Bitrate"), bitrateText(Double(bitrate)))) }
        if let bits { videoRows.append((String(localized: "Bit depth"), "\(bits)-bit")) }
        if let transfer, transfer == "HLG" || transfer == "PQ" { videoRows.append(("HDR", transfer)) }
        if let description {
            if let primaries = CMFormatDescriptionGetExtension(description, extensionKey: kCMFormatDescriptionExtension_ColorPrimaries) as? String {
                videoRows.append((String(localized: "Color primaries"), colorStandard(primaries)))
            }
            if let transferRaw = CMFormatDescriptionGetExtension(description, extensionKey: kCMFormatDescriptionExtension_TransferFunction) as? String {
                videoRows.append((String(localized: "Transfer function"), transferName(transferRaw) ?? transferRaw))
            }
            if let matrix = CMFormatDescriptionGetExtension(description, extensionKey: kCMFormatDescriptionExtension_YCbCrMatrix) as? String {
                videoRows.append((String(localized: "Color matrix"), colorStandard(matrix)))
            }
            if let full = CMFormatDescriptionGetExtension(description, extensionKey: kCMFormatDescriptionExtension_FullRangeVideo) as? NSNumber {
                videoRows.append((String(localized: "Color range"), full.boolValue ? String(localized: "Full") : String(localized: "Limited")))
            }
        }
        let degrees = (Int((atan2(transform.b, transform.a) * 180 / .pi).rounded()) + 360) % 360
        videoRows.append((String(localized: "Rotation"), "\(degrees)°"))

        if let codec { title.append(codec) }
        if width > 0 && height > 0 { title.append(resolutionName(width, height) ?? "\(width) × \(height)") }
        if rate > 0 { title.append("\(Int(Double(rate).rounded())) fps") }
        if let profile { subtitle.append(String(localized: "\(profile) profile")) }
        if let transfer, transfer == "HLG" || transfer == "PQ" { subtitle.append(transfer) }
        if rate > 0 { chips.append("\(plain(Double(rate))) fps") }
        if bitrate > 0 { chips.append(bitrateText(Double(bitrate))) }
        if transfer == "HLG" || transfer == "PQ" { chips.append(String(localized: "\(bits ?? 10)-bit HDR")) }
    }

    if let audio = tracks.first(where: { $0.mediaType == .audio }) {
        let description = (try? await audio.load(.formatDescriptions))?.first
        let bitrate = (try? await audio.load(.estimatedDataRate)) ?? 0
        var chip: [String] = []
        if let description {
            let fourCC = CMFormatDescriptionGetMediaSubType(description)
            let codec = audioCodec(fourCC)
            audioRows.append((String(localized: "Codec"), codec))
            subtitle.append(String(localized: "\(codec) audio"))
            if let basic = CMAudioFormatDescriptionGetStreamBasicDescription(description)?.pointee {
                let channels = Int(basic.mChannelsPerFrame)
                if channels > 0 {
                    let name = channelsName(channels)
                    audioRows.append((String(localized: "Channels"), name.map { "\(channels) (\($0))" } ?? "\(channels)"))
                }
                if basic.mSampleRate > 0 {
                    let khz = "\(plain(basic.mSampleRate / 1000)) kHz"
                    audioRows.append((String(localized: "Sample rate"), khz))
                    chip.append(khz)
                }
                if channels > 0 { chip.append(channelsName(channels) ?? String(localized: "\(channels) channels")) }
            }
        }
        if bitrate > 0 { audioRows.append((String(localized: "Bitrate"), bitrateText(Double(bitrate)))) }
        if !chip.isEmpty { chips.append(chip.joined(separator: " ")) }
    }

    if duration > 0 {
        fileRows.append((String(localized: "Duration"), durationText(duration)))
        if let fileBytes, fileBytes > 0 {
            fileRows.append((String(localized: "Overall bitrate"), bitrateText(Double(fileBytes) * 8 / duration)))
        }
    }
    let kinds = tracks.map(\.mediaType).reduce(into: [AVMediaType]()) { if !$0.contains($1) { $0.append($1) } }
    if !kinds.isEmpty { fileRows.append((String(localized: "Tracks"), kinds.map(trackName).joined(separator: ", "))) }
    fileRows += await videoMetadata(asset)

    var sections: [DetailSection] = []
    if !videoRows.isEmpty { sections.append(DetailSection(name: String(localized: "Video"), rows: videoRows)) }
    if !audioRows.isEmpty { sections.append(DetailSection(name: String(localized: "Audio"), rows: audioRows)) }
    if !fileRows.isEmpty { sections.append(DetailSection(name: String(localized: "File"), rows: fileRows)) }
    return OriginalDetails(
        title: title.isEmpty ? nil : title.joined(separator: " · "),
        subtitle: subtitle.isEmpty ? nil : subtitle.joined(separator: " · "),
        chips: chips,
        sections: sections
    )
}

/// Recorded, location, make, model and software, from the common and QuickTime metadata.
private func videoMetadata(_ asset: AVAsset) async -> [(label: String, value: String)] {
    var items = (try? await asset.load(.commonMetadata)) ?? []
    items += (try? await asset.load(.metadata)) ?? []
    func value(_ identifiers: [AVMetadataIdentifier]) async -> String? {
        for identifier in identifiers {
            for item in AVMetadataItem.metadataItems(from: items, filteredByIdentifier: identifier) {
                if let text = try? await item.load(.stringValue), !text.cleaned.isEmpty { return text.cleaned }
            }
        }
        return nil
    }
    var rows: [(label: String, value: String)] = []
    if let created = await value([.quickTimeMetadataCreationDate, .commonIdentifierCreationDate, .quickTimeUserDataCreationDate]) {
        rows.append((String(localized: "Recorded"), recordedText(created)))
    }
    if let location = await value([.quickTimeMetadataLocationISO6709, .quickTimeUserDataLocationISO6709, .commonIdentifierLocation]) {
        rows.append((String(localized: "Location"), location))
    }
    if let make = await value([.quickTimeMetadataMake, .commonIdentifierMake]) { rows.append((String(localized: "Make"), make)) }
    if let model = await value([.quickTimeMetadataModel, .commonIdentifierModel]) { rows.append((String(localized: "Model"), model)) }
    if let software = await value([.quickTimeMetadataSoftware, .commonIdentifierSoftware]) {
        rows.append((String(localized: "Software"), software))
    }
    return rows
}

/// "2026-10-12T09:41:05+0300" → "2026-10-12 09:41:05 (+03:00)", in the time zone it was recorded in.
private func recordedText(_ raw: String) -> String {
    guard raw.count >= 19, raw.dropFirst(10).first == "T" else { return raw }
    let shown = raw.prefix(19).replacingOccurrences(of: "T", with: " ")
    var zone = String(raw.dropFirst(19).drop { $0 != "+" && $0 != "-" && $0 != "Z" })
    if zone.isEmpty { return shown }
    if zone == "Z" { return "\(shown) (UTC)" }
    if zone.count == 5 { zone.insert(":", at: zone.index(zone.startIndex, offsetBy: 3)) }
    return "\(shown) (\(zone))"
}

private func fourCCString(_ code: FourCharCode) -> String {
    let bytes = [24, 16, 8, 0].map { UInt8((code >> $0) & 0xFF) }
    return String(bytes: bytes, encoding: .ascii)?.cleaned ?? String(code)
}

/// "HEVC", "H.264", "ProRes", or the FourCC itself.
private func videoCodec(_ code: FourCharCode) -> String {
    switch fourCCString(code) {
    case "hvc1", "hev1": return "HEVC"
    case "dvh1", "dvhe": return "Dolby Vision"
    case "avc1", "avc3": return "H.264"
    case "av01": return "AV1"
    case "vp09": return "VP9"
    case "mp4v": return "MPEG-4"
    case "ap4h", "ap4x", "apch", "apcn", "apcs", "apco": return "ProRes"
    case "jpeg": return "Motion JPEG"
    case let other: return other
    }
}

private func codecLongName(_ code: FourCharCode) -> String? {
    switch fourCCString(code) {
    case "hvc1", "hev1": return "HEVC (H.265)"
    case "avc1", "avc3": return "H.264 (AVC)"
    default: return nil
    }
}

/// HEVC's and H.264's profile, from the decoder configuration in the sample description ("Main 10", "High").
private func videoProfile(_ description: CMFormatDescription, fourCC: FourCharCode) -> String? {
    guard let atoms = CMFormatDescriptionGetExtension(description, extensionKey: kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms) as? [String: Any]
    else { return nil }
    switch fourCCString(fourCC) {
    case "hvc1", "hev1":
        guard let config = atoms["hvcC"] as? Data, config.count > 1 else { return nil }
        switch config[config.startIndex + 1] & 0x1F {
        case 1: return "Main"
        case 2: return "Main 10"
        case 3: return "Main Still Picture"
        case 4: return "Range Extensions"
        default: return nil
        }
    case "avc1", "avc3":
        guard let config = atoms["avcC"] as? Data, config.count > 1 else { return nil }
        switch config[config.startIndex + 1] {
        case 66: return "Baseline"
        case 77: return "Main"
        case 88: return "Extended"
        case 100: return "High"
        case 110: return "High 10"
        case 122: return "High 4:2:2"
        default: return nil
        }
    default:
        return nil
    }
}

/// "HLG", "PQ", "BT.709".
private func transferName(_ raw: String) -> String? {
    if raw == (kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG as String) { return "HLG" }
    if raw == (kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ as String) { return "PQ" }
    return colorStandard(raw)
}

/// "ITU_R_2020" → "BT.2020", "ITU_R_709_2" → "BT.709", "P3_D65" → "Display P3".
private func colorStandard(_ raw: String) -> String {
    if raw.hasPrefix("ITU_R_2020") { return "BT.2020" }
    if raw.hasPrefix("ITU_R_709") { return "BT.709" }
    if raw.hasPrefix("ITU_R_601") { return "BT.601" }
    if raw == "P3_D65" { return "Display P3" }
    if raw == "DCI_P3" { return "DCI-P3" }
    if raw.hasPrefix("SMPTE_C") { return "SMPTE C" }
    return raw.replacingOccurrences(of: "_", with: " ")
}

private func audioCodec(_ code: FourCharCode) -> String {
    switch code {
    case kAudioFormatMPEG4AAC: return "AAC"
    case kAudioFormatMPEG4AAC_HE, kAudioFormatMPEG4AAC_HE_V2: return "HE-AAC"
    case kAudioFormatAC3: return "AC-3"
    case kAudioFormatEnhancedAC3: return "E-AC-3"
    case kAudioFormatLinearPCM: return "PCM"
    case kAudioFormatAppleLossless: return "ALAC"
    case kAudioFormatOpus: return "Opus"
    case kAudioFormatFLAC: return "FLAC"
    case kAudioFormatMPEGLayer3: return "MP3"
    default:
        let name = fourCCString(code)
        return name == "apac" ? String(localized: "Apple spatial audio") : name
    }
}

private func channelsName(_ channels: Int) -> String? {
    switch channels {
    case 1: return String(localized: "mono")
    case 2: return String(localized: "stereo")
    case 6: return "5.1"
    case 8: return "7.1"
    default: return nil
    }
}

/// "4K", "1080p"; nil for sizes without a usual name.
private func resolutionName(_ width: Int, _ height: Int) -> String? {
    let short = min(width, height), long = max(width, height)
    if long >= 7680 { return "8K" }
    if long >= 3840 || short >= 2160 { return "4K" }
    if short >= 1440 { return "1440p" }
    if short >= 1080 { return "1080p" }
    if short >= 720 { return "720p" }
    if short >= 480 { return "480p" }
    return nil
}

/// "41.8 Mb/s", "256 kb/s".
private func bitrateText(_ bitsPerSecond: Double) -> String {
    if bitsPerSecond >= 1_000_000 { return String(format: "%.1f Mb/s", locale: Locale(identifier: "en_US_POSIX"), bitsPerSecond / 1_000_000) }
    return "\(Int((bitsPerSecond / 1000).rounded())) kb/s"
}

/// "0:38.2", "1:02:03.0".
private func durationText(_ seconds: Double) -> String {
    let tenths = Int((seconds * 10).rounded())
    let whole = tenths / 10
    let hours = whole / 3600, minutes = whole % 3600 / 60, secs = whole % 60
    let main = hours > 0 ? String(format: "%d:%02d:%02d", hours, minutes, secs) : String(format: "%d:%02d", minutes, secs)
    return "\(main).\(tenths % 10)"
}

private func trackName(_ type: AVMediaType) -> String {
    switch type {
    case .video: return String(localized: "Video")
    case .audio: return String(localized: "Audio")
    case .metadata: return String(localized: "Metadata")
    case .timecode: return String(localized: "Timecode")
    default: return type.rawValue
    }
}
