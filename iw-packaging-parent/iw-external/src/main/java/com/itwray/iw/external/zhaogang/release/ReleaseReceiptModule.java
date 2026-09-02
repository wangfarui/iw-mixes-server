package com.itwray.iw.external.zhaogang.release;

import com.itwray.iw.external.zhaogang.release.ReleaseReceiptModels.Context;
import com.itwray.iw.external.zhaogang.release.ReleaseReceiptModels.Receipt;

public interface ReleaseReceiptModule {

    Receipt receipt(Context context, String releaseId);

    Receipt acknowledge(Context context, String releaseId);
}
