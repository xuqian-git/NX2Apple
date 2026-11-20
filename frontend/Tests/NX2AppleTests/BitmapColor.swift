import AppKit

extension NSBitmapImageRep {
    /// The pixel at (x, y) in sRGB, read in the colour space the bitmap was rendered in.
    ///
    /// A view's cached display is rendered in its window's colour space, which follows the main display (for example
    /// "Color LCD", a wide-gamut space, on a built-in Retina display). `colorAt` returns the raw components tagged as
    /// calibrated RGB whatever `colorSpace` says, so converting its result to sRGB shifts the colour on such displays:
    /// a pure red reads with green near 0.3. Tagging the components with `colorSpace` first gives the same values on
    /// every display.
    func sRGBColor(x: Int, y: Int) -> NSColor? {
        guard let raw = colorAt(x: x, y: y) else { return nil }
        // the components are the colour space's channels followed by alpha
        guard raw.numberOfComponents == colorSpace.numberOfColorComponents + 1 else { return raw.usingColorSpace(.sRGB) }
        var components = [CGFloat](repeating: 0, count: raw.numberOfComponents)
        raw.getComponents(&components)
        return NSColor(colorSpace: colorSpace, components: components, count: components.count).usingColorSpace(.sRGB)
    }
}
