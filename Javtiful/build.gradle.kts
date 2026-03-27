version = 3

cloudstream {
    authors     = listOf("vsc")
    language    = "en"
    description = "Javtiful"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://javtiful.com/images/logo/new_jt_logo.png"
}