import SwiftUI

public struct PreparingStreamView: View {
    public let title: String
    public let subtitle: String
    public let secondsRemaining: Int
    public let progressPercent: Int
    public let onCancel: () -> Void

    public var body: some View {
        ZStack {
            Color.black.opacity(0.75)
                .ignoresSafeArea()

            VStack(spacing: 16) {
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle(tint: Color(hex: "#38BDF8")))
                    .scaleEffect(1.5)
                    .padding(.top, 8)
                    .padding(.bottom, 6)

                Text(title)
                    .font(.system(size: 19, weight: .bold))
                    .foregroundColor(Color(hex: "#38BDF8"))
                    .multilineTextAlignment(.center)

                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundColor(Color(hex: "#94A3B8"))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12)

                Text("Starting stream in \(secondsRemaining)s...")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(Color(hex: "#F8FAFC"))
                    .padding(.top, 4)

                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(Color(hex: "#38BDF8").opacity(0.2))
                            .frame(height: 8)

                        let width = geo.size.width * CGFloat(30 - secondsRemaining) / 30.0
                        RoundedRectangle(cornerRadius: 4)
                            .fill(Color(hex: "#38BDF8"))
                            .frame(width: max(0, min(width, geo.size.width)), height: 8)
                    }
                }
                .frame(height: 8)
                .padding(.horizontal, 16)

                Text("Hardware Transcode: \(progressPercent)%")
                    .font(.system(size: 12))
                    .foregroundColor(Color(hex: "#64748B"))

                Button(action: onCancel) {
                    Text("Cancel")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(Color(hex: "#F8FAFC"))
                        .frame(maxWidth: .infinity)
                        .frame(height: 40)
                        .background(Color(hex: "#24324D"))
                        .cornerRadius(8)
                }
                .padding(.top, 6)
            }
            .padding(24)
            .background(Color(hex: "#151D30"))
            .cornerRadius(16)
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(Color(hex: "#24324D"), lineWidth: 1)
            )
            .padding(.horizontal, 32)
        }
    }
}
