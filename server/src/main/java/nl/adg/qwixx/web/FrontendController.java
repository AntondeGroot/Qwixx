package nl.adg.qwixx.web;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class FrontendController implements ErrorController {

    // Any error that reaches the framework fallback here is an unmatched route (a 404 for an SPA
    // deep link) — application errors are returned as JSON by GlobalExceptionHandler and never get
    // this far. Forward to the SPA so Angular's client-side router handles the URL.
    @RequestMapping("/error")
    public String error() {
        return "forward:/index.html";
    }
}
