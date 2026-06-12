import Foundation
import AppKit

// macOS pano (veya test için dosya argümanı) zengin içeriğini, GÖMÜLÜ imajlar
// base64 data-uri olacak şekilde HTML'e çevirir ve stdout'a basar.
//
// NEDEN: Pages/TextEdit/Mail panoya HTML KOYMAZ (yalnız RTF/RTFD). textutil
// RTFD→HTML çevirisinde imajları sidecar DOSYA referansı (<img src="file:///...">)
// olarak verir; UDE parser'ı (macospasterich.HtmlToUde) yalnız data-uri imaj
// kabul eder. Cocoa NSAttributedString panoyu ekleriyle (NSTextAttachment, bellekte
// NSFileWrapper) okur → her ekin baytını base64 data-uri'ye gömüp file:/// refini
// değiştiririz. Üretilen HTML zaten <style> class kuralları + tablo biçimidir
// (HtmlToUde sorunsuz çözer). Argümansız çalışınca NSPasteboard.general okur
// (yapıştırma anında pano hâlâ doludur). RichPaste.insertRtf bunu jar'dan çıkarıp
// alt süreç olarak çağırır.
func attributed() -> NSAttributedString? {
    let args = CommandLine.arguments
    if args.count > 1 {   // test yolu: rtfd/rtf dosyası
        let url = URL(fileURLWithPath: args[1])
        if url.pathExtension == "rtfd" {
            if let fw = try? FileWrapper(url: url) {
                return NSAttributedString(rtfdFileWrapper: fw, documentAttributes: nil)
            }
        }
        if let d = try? Data(contentsOf: url) {
            return try? NSAttributedString(data: d, options: [:], documentAttributes: nil)
        }
        return nil
    }
    let pb = NSPasteboard.general
    if let objs = pb.readObjects(forClasses: [NSAttributedString.self], options: nil) as? [NSAttributedString],
       let a = objs.first, a.length > 0 {
        return a
    }
    return nil
}

guard let attr = attributed() else {
    FileHandle.standardError.write("zengin içerik yok\n".data(using: .utf8)!)
    exit(1)
}

// ekleri topla: dosyaadı → base64 data-uri
var map: [String: String] = [:]
attr.enumerateAttribute(.attachment, in: NSRange(location: 0, length: attr.length)) { val, _, _ in
    guard let att = val as? NSTextAttachment, let fw = att.fileWrapper, let bytes = fw.regularFileContents else { return }
    let name = fw.preferredFilename ?? "img"
    let ext = (name as NSString).pathExtension.lowercased()
    let mime = (ext == "jpg" || ext == "jpeg") ? "image/jpeg"
        : ext == "gif" ? "image/gif"
        : (ext == "tiff" || ext == "tif") ? "image/tiff"
        : "image/png"
    map[name] = "data:\(mime);base64," + bytes.base64EncodedString()
}

guard let data = try? attr.data(from: NSRange(location: 0, length: attr.length),
    documentAttributes: [.documentType: NSAttributedString.DocumentType.html,
                         .characterEncoding: String.Encoding.utf8.rawValue]) else {
    FileHandle.standardError.write("html üretilemedi\n".data(using: .utf8)!)
    exit(1)
}
var html = String(data: data, encoding: .utf8) ?? ""
for (name, uri) in map {
    html = html.replacingOccurrences(of: "file:///" + name, with: uri)
    html = html.replacingOccurrences(of: "file://localhost/" + name, with: uri)
}
FileHandle.standardOutput.write(html.data(using: .utf8)!)
