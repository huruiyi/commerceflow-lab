import { Component } from '@angular/core';
import { NavigationStart, Router } from '@angular/router';
import { AccountService } from './services/account.service';

@Component({
  standalone: false,
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent {
  title = 'project';
  //globalUrl = ''
  isLoggedIn = false;

  constructor (private router :Router, private accountService :AccountService){
    /* this.router.events.subscribe(evts => {
      if (evts instanceof NavigationStart) {
        this.globalUrl = evts.url;
      }
    }) */
    this.isLoggedIn = accountService.isLoggedIn()
  }


}
