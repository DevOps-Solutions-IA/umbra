package app.umbra.content;

/** Invalid authenticated peer payload for rejection tests; absent from application APKs. */
public final class SyntheticDocuments {
    private SyntheticDocuments() {}
    public static RestrictedContentService.Prepared invalidRaster(Runnable authorization) {
        return new RestrictedContentService.Prepared(RestrictedPayload.Format.PDF_PAGES,
            DocumentPages.pack(java.util.List.of(new byte[8])),authorization);
    }
}
