package com.example.shop.web;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import com.example.shop.member.MemberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/** Login page and sign-up (REQ-F-002). The login POST itself is handled by Spring Security. */
@Controller
public class AuthController {

    private final MemberService memberService;

    public AuthController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }

    @GetMapping("/signup")
    public String signupPage(@ModelAttribute("form") SignupForm form) {
        return "auth/signup";
    }

    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute("form") SignupForm form, BindingResult binding) {
        if (binding.hasErrors()) {
            return "auth/signup";
        }
        try {
            memberService.register(form.getEmail(), form.getPassword(), form.getName());
        } catch (ShopException e) {
            if (e.getErrorCode() != ErrorCode.DUPLICATE_EMAIL) {
                throw e;
            }
            binding.rejectValue("email", "duplicate", e.getMessage());
            return "auth/signup";
        }
        return "redirect:/login?registered";
    }

    public static class SignupForm {

        @NotBlank
        @Email
        @Size(max = 100)
        private String email;

        @NotBlank
        @Size(min = 8, max = 64)
        private String password;

        @NotBlank
        @Size(max = 50)
        private String name;

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
