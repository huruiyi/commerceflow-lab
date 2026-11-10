import { Component, OnInit } from '@angular/core';
import { SeoService } from '../services/seo.service';

@Component({
  standalone: false,
  selector: 'app-errorpage',
  templateUrl: './errorpage.component.html',
  styleUrls: ['./errorpage.component.css']
})
export class ErrorpageComponent implements OnInit {

  constructor(private seoService: SeoService) {
    seoService.setTitle("Error Page")
    seoService.setMeta("The page that occurs when there is no  matching page...")
  }

  ngOnInit(): void {
  }

}
