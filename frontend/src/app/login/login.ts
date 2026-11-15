import { Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../filegrid-api';
import { SessionStore } from '../session';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  templateUrl: './login.html',
  styleUrl: './login.css',
})
export class Login {
  private readonly session = inject(SessionStore);

  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected submit(): void {
    if (this.busy() || !this.username().trim() || !this.password()) return;
    this.busy.set(true);
    this.error.set(null);
    this.session.login(this.username().trim(), this.password(), (err) => {
      this.busy.set(false);
      if (err instanceof HttpErrorResponse && err.status === 401) {
        this.error.set('Utilizator sau parolă greșită.');
      } else if (err instanceof HttpErrorResponse && err.status === 429) {
        this.error.set('Prea multe încercări. Așteaptă puțin și încearcă din nou.');
      } else {
        this.error.set(errorMessage(err));
      }
    });
  }
}
