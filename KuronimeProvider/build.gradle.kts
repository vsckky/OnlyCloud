// use an integer for version numbers
version = 23


cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    // description = "Lorem Ipsum"
     authors = listOf("vsc")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABwAAAAcCAMAAABF0y+mAAAAnFBMVEVHcEzq6ur39/f09PSIh4dxcXGztLP5+fnPz8/19fV9fXsAAAAhIR5lZWXNzc3d3dzHx8YuLiw1NTRKSkkNDQ17e3uCg4IoKCRxcXFQUE06OzecnJxDQ0Lf4N9iYmGWlpbAwL9cXFybnJSNjYzz8/M9PTump6VnaGVWV1cWFhXz9PNKSz1RUzVvcjedoVK7u7uGiUh3dnX///+lpaWLP+tsAAAANHRSTlMAAwknlJN2E1kQ7P//9VRFcP///f/A2f/6/P+3/2nt9UH9TsAz/6Lm//8e//349Yr30xfAm2Aq5gAAAQdJREFUeAFiGFyAkQmLIKA0ukpAIASAAIq5MWx3gN2t97+blP3pbO+j6fUHwx4hlu38muWCUs//hW5ASEijOEmz30p5MiQZirKq3c53vQZoGUc8clFaXzhuAZTilgKTnh5c3vhi0FZvOoMIpZBp5wqDBeqcLVfrAjKRxiLQ2OI9i416bJjCLf/AXaoeqUbSPGF/eCIMhvQxjOIItAZPGk+yzllQigioN+9orWQXewiRianGRuE8wXuiQj9nnTfc0E2qcKHxonazVwmJ4jqu20UMpKZPfjVTWcRmiG2LR+KxQicvYFJUeGapsHNbTyaeNxE37q5W3kRlfTN7vH3kalnW1bxb5I/cAQbVHdwd/OHrAAAAAElFTkSuQmCC"
}