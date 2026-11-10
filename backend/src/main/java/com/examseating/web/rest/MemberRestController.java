package com.examseating.web.rest;

import com.examseating.dto.AccountDtos.CreateMemberRequest;
import com.examseating.dto.AccountDtos.MemberView;
import com.examseating.dto.AccountDtos.UpdateMemberRequest;
import com.examseating.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The members of an institution — the people who share its roster, its halls
 * and its seating plans.
 *
 * <p>Reading is open to every member; writing is for the administrator of the
 * institution. The platform administrator manages accounts through
 * {@code /api/v1/admin/users} instead.</p>
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class MemberRestController {

    private final MemberService memberService;

    /** GET /api/v1/users — the members of this institution. */
    @GetMapping
    public List<MemberView> members() {
        return memberService.members();
    }

    /** GET /api/v1/users/{id} — one member. */
    @GetMapping("/{id}")
    public MemberView member(@PathVariable Long id) {
        return memberService.member(id);
    }

    /** POST /api/v1/users — add a member (the administrator only). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public MemberView create(@Valid @RequestBody CreateMemberRequest request) {
        return memberService.create(request);
    }

    /** PUT /api/v1/users/{id} — change a member's role, status, name, address or password. */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public MemberView update(@PathVariable Long id, @RequestBody UpdateMemberRequest request) {
        return memberService.update(id, request);
    }

    /** DELETE /api/v1/users/{id} — remove a member (the administrator only). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void remove(@PathVariable Long id) {
        memberService.remove(id);
    }
}
