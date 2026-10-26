// Renders sample_cjk.jpg, the Chinese/Japanese/Korean OCR sample (PLAN 4.13), with CoreText so the scripts are
// shaped by real system fonts. Run on macOS: swift render_sample_cjk.swift <out.jpg>
// The known lines are what the OCR device test and the end-to-end search test look for.
import AppKit
import CoreText

let outPath = CommandLine.arguments[1]
let W = 1240, H = 1754 // A4 at 150 dpi, like sample_en_hi.jpg
let ctx = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
ctx.setFillColor(CGColor(red: 0.985, green: 0.98, blue: 0.965, alpha: 1))
ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))

func draw(_ s: String, font: String, size: CGFloat, yTop: CGFloat) {
    let f = CTFontCreateWithName(font as CFString, size, nil)
    let attrs: [NSAttributedString.Key: Any] = [.font: f, .foregroundColor: CGColor(red: 0.08, green: 0.08, blue: 0.1, alpha: 1)]
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: s, attributes: attrs))
    ctx.textPosition = CGPoint(x: 110, y: CGFloat(H) - yTop - size) // CoreGraphics origin is bottom-left
    CTLineDraw(line, ctx)
}

draw("HEYCAM CJK SAMPLE", font: "Helvetica-Bold", size: 54, yTop: 120)
draw("東京駅で会う", font: "HiraginoSans-W3", size: 64, yTop: 260)
draw("明日の午後三時です", font: "HiraginoSans-W3", size: 64, yTop: 370)
draw("서울역에서 만나요", font: "AppleSDGothicNeo-Regular", size: 64, yTop: 500)
draw("감사합니다", font: "AppleSDGothicNeo-Regular", size: 64, yTop: 610)
draw("北京欢迎你", font: "PingFangSC-Regular", size: 64, yTop: 740)
draw("Meeting at Tokyo Station, 3 pm", font: "Helvetica", size: 40, yTop: 870)

let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
let jpeg = [UInt8](rep.representation(using: .jpeg, properties: [.compressionFactor: 0.9])!)

// AppKit adds an Exif segment; staged pages carry no metadata (PLAN P4), so keep only JFIF (APP0) and the image.
var out: [UInt8] = [0xFF, 0xD8]
var i = 2
while i + 4 <= jpeg.count {
    let marker = jpeg[i + 1]
    if marker == 0xDA { out += jpeg[i...]; break } // start of scan: the rest is image data
    let length = Int(jpeg[i + 2]) << 8 | Int(jpeg[i + 3])
    let isMetadata = (0xE1...0xEF).contains(marker) || marker == 0xFE
    if !isMetadata { out += jpeg[i..<(i + 2 + length)] }
    i += 2 + length
}
try! Data(out).write(to: URL(fileURLWithPath: outPath))
print("wrote \(outPath) \(W)x\(H)")
