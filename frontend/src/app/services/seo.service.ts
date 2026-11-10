import { Injectable } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';

@Injectable({
  providedIn: 'root'
})
export class SeoService {

  constructor(private title :Title, private meta :Meta) { }

  setTitle(title :string) {
    this.title.setTitle (title);
  }

  setMeta(metaContent :string) {
    this.meta.updateTag({name: "description", content: metaContent})
  }
}
