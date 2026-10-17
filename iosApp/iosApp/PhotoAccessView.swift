import SwiftUI

/// Asks for the photo library once, before the home screen. See docs/screens/photo-access.md.
struct PhotoAccessView: View {
    var onAllow: () -> Void
    var onNotNow: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    PhotoStack()
                        .frame(maxWidth: .infinity)
                        .frame(height: 240)
                        .padding(.top, 24)
                    Text("Your journal starts with your photos and videos")
                        .font(.largeTitle.weight(.semibold))
                        .padding(.top, 24)
                    Text("Journal needs access to your library to show your moments by day and back them up to your own server.")
                        .foregroundStyle(.secondary)
                        .padding(.top, 12)
                    VStack(alignment: .leading, spacing: 18) {
                        Point(systemName: "calendar", title: "Your moments, day by day",
                              detail: "Photos and videos, grouped like a diary.")
                        Point(systemName: "lock", title: "Backed up to your server",
                              detail: "Nothing is uploaded until you turn backup on.")
                    }
                    .padding(.top, 28)
                }
                .padding(.horizontal, 28)
            }
            VStack(spacing: 6) {
                Button(action: onAllow) {
                    Text("Allow access")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .frame(height: 54)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                Button("Not now", action: onNotNow)
                    .font(.headline)
                    .frame(height: 48)
                Text("You can pick specific photos instead of your whole library.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 28)
            .padding(.bottom, 12)
        }
        .background(Color(.systemGroupedBackground))
    }
}

/// Three tilted photo cards with a backup badge: what the app does, before any words.
private struct PhotoStack: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 18)
                .fill(Color.accentColor.opacity(0.18))
                .frame(width: 150, height: 186)
                .rotationEffect(.degrees(-9))
                .offset(x: -62)
            RoundedRectangle(cornerRadius: 18)
                .fill(Color.orange.opacity(0.22))
                .frame(width: 150, height: 186)
                .rotationEffect(.degrees(8))
                .offset(x: 62)
            VStack {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.accentColor.opacity(0.3))
                    .frame(height: 138)
                    .overlay(Image(systemName: "photo").font(.system(size: 36)).foregroundStyle(Color.accentColor))
                Spacer()
            }
            .padding(10)
            .frame(width: 164, height: 204)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
            .shadow(color: .black.opacity(0.15), radius: 14, y: 8)
            Image(systemName: "icloud.and.arrow.up")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 40, height: 40)
                .background(Color.accentColor, in: Circle())
                .overlay(Circle().stroke(Color(.systemGroupedBackground), lineWidth: 4))
                .offset(x: 72, y: 92)
        }
        .accessibilityHidden(true)
    }
}

private struct Point: View {
    let systemName: String
    let title: LocalizedStringKey
    let detail: LocalizedStringKey

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: systemName)
                .foregroundStyle(Color.accentColor)
                .frame(width: 36, height: 36)
                .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}

#Preview {
    PhotoAccessView(onAllow: {}, onNotNow: {})
}
