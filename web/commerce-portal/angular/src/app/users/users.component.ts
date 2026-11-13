import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { IUser } from 'src/model/IUser';
import { ApiService } from '../services/api.service';
import { SeoService } from '../services/seo.service';

@Component({
  standalone: false,
  selector: 'app-users',
  templateUrl: './users.component.html',
  styleUrls: ['./users.component.css'],
})
export class UsersComponent implements OnInit {
  users: IUser[] = [];
  filteredUsers: IUser[] = [];
  searchText = '';

  constructor(
    private api: ApiService,
    private seoService: SeoService,
    private changeDetector: ChangeDetectorRef,
  ) {
    seoService.setTitle('User Page');
    seoService.setMeta('The page that lists users...');

    api.getUsers().subscribe({
      next: (res) => {
        this.users = res;
        this.filteredUsers = res;
        this.changeDetector.markForCheck();
      },
      error: (err) => {
        console.log(err);
      },
    });
  }

  ngOnInit(): void {}

  filterUsers() {
    this.filteredUsers = this.users.filter(
      (element) =>
        element.name.toLowerCase().includes(this.searchText.toLowerCase()) ||
        element.email.toLowerCase().includes(this.searchText.toLowerCase()) ||
        element.phone.toLowerCase().includes(this.searchText.toLowerCase()),
    );
  }
}
