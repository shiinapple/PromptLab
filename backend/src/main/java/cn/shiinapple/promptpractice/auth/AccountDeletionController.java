package cn.shiinapple.promptpractice.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account/deletion")
class AccountDeletionController {
    private final AccountDeletion deletion;

    AccountDeletionController(AccountDeletion deletion) {
        this.deletion = deletion;
    }

    @PostMapping
    ResponseEntity<AccountDeletion.DeletionStatus> request(HttpServletRequest request) {
        var result = deletion.request(Sessions.owner(request));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.status());
    }

    @GetMapping
    AccountDeletion.DeletionStatus status(HttpServletRequest request) {
        return deletion.status(Sessions.owner(request));
    }

    @DeleteMapping
    ResponseEntity<Void> withdraw(HttpServletRequest request) {
        deletion.withdraw(Sessions.owner(request));
        return ResponseEntity.noContent().build();
    }
}
