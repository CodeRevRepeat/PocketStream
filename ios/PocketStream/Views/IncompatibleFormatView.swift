import SwiftUI

public struct IncompatibleFormatView: View {
    public let mediaTitle: String
    public let tvName: String
    public let reasons: [String]
    public let onEncodeAndStream: () -> Void
    public let onDirectPlay: () -> Void
    public let onCancel: () -> Void

    public var body: some View {
        ZStack {
            Color.black.opacity(0.75)
                .ignoresSafeArea()

            VStack(alignment: .leading, spacing: 14) {
                Text(mediaTitle)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(Color(hex: "#F8FAFC"))
                    .lineLimit(2)

                Text("This video is not supported directly by your \(tvName):")
                    .font(.system(size: 13))
                    .foregroundColor(Color(hex: "#94A3B8"))

                VStack(alignment: .leading, spacing: 6) {
                    ForEach(reasons, id: \.self) { reason in
                        HStack(alignment: .top, spacing: 6) {
                            Text("•")
                                .foregroundColor(Color(hex: "#38BDF8"))
                            Text(reason)
                                .font(.system(size: 12))
                                .foregroundColor(Color(hex: "#CBD5E1"))
                        }
                    }
                }
                .padding(.vertical, 4)

                Text("Your iPhone can real-time hardware-encode this video using its hardware encoder for smooth, stutter-free playback on your TV.")
                    .font(.system(size: 12))
                    .foregroundColor(Color(hex: "#94A3B8"))

                VStack(spacing: 8) {
                    Button(action: onEncodeAndStream) {
                        Text("Encode & Stream (Recommended)")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 42)
                            .background(Color(hex: "#38BDF8"))
                            .cornerRadius(8)
                    }

                    Button(action: onDirectPlay) {
                        Text("Direct Play Anyway")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(Color(hex: "#94A3B8"))
                            .frame(maxWidth: .infinity)
                            .frame(height: 38)
                            .background(Color(hex: "#1E293B"))
                            .cornerRadius(8)
                    }

                    Button(action: onCancel) {
                        Text("Cancel")
                            .font(.system(size: 13))
                            .foregroundColor(Color(hex: "#64748B"))
                            .frame(maxWidth: .infinity)
                            .frame(height: 36)
                    }
                }
                .padding(.top, 6)
            }
            .padding(22)
            .background(Color(hex: "#151D30"))
            .cornerRadius(16)
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(Color(hex: "#24324D"), lineWidth: 1)
            )
            .padding(.horizontal, 28)
        }
    }
}
