import { Component, computed, inject, input, signal } from '@angular/core';
import { I18nService } from '../services/i18n.service';

const demoPhotoUrl =
  /^https:\/\/picsum\.photos\/seed\/(laptop|keyboard|monitor|dock|headset|notebook)\/(?:800\/500|160\/100)$/;

@Component({
  selector: 'app-catalog-image',
  template: `
    @if (demoPhoto(); as photo) {
      <span
        class="demo-photo"
        [attr.data-photo]="photo"
        [attr.role]="alt() ? 'img' : null"
        [attr.aria-label]="alt() || null"
        [attr.aria-hidden]="alt() ? null : 'true'"
      ></span>
    } @else if (source(); as url) {
      <img [src]="url" [alt]="alt()" [loading]="loading()" decoding="async" (error)="markUnavailable(url)" />
    } @else {
      <span class="image-fallback" [attr.aria-hidden]="alt() === '' ? 'true' : null">
        <i class="bi bi-image" aria-hidden="true"></i>
        {{ i18n.t('catalogImageUnavailable') }}
      </span>
    }
  `,
  styles: `
    :host {
      position: relative;
      display: grid;
      min-width: 0;
      min-height: 0;
      overflow: hidden;
      background: #edf1eb;
    }

    img {
      display: block;
      width: 100%;
      height: 100%;
      min-height: 0;
      object-fit: var(--catalog-image-fit, cover);
    }

    .demo-photo {
      position: absolute;
      inset: 50% 0 auto;
      width: 100%;
      aspect-ratio: 1;
      transform: translateY(-50%);
      background: url('/assets/demo-product-photography.jpg') no-repeat;
      background-size: 300% 200%;
    }

    .demo-photo[data-photo='laptop'] {
      background-position: 0 0;
    }

    .demo-photo[data-photo='keyboard'] {
      background-position: 50% 0;
    }

    .demo-photo[data-photo='monitor'] {
      background-position: 100% 0;
    }

    .demo-photo[data-photo='dock'] {
      background-position: 0 100%;
    }

    .demo-photo[data-photo='headset'] {
      background-position: 50% 100%;
    }

    .demo-photo[data-photo='notebook'] {
      background-position: 100% 100%;
    }

    .image-fallback {
      display: grid;
      align-content: center;
      justify-items: center;
      gap: 0.4rem;
      padding: 0.75rem;
      color: #52615b;
      font-size: 0.8125rem;
    }

    .image-fallback i {
      font-size: 1.375rem;
    }

    :host(.compact) .image-fallback {
      gap: 0;
      padding: 0;
      font-size: 0;
    }
  `,
})
export class CatalogImageComponent {
  readonly i18n = inject(I18nService);
  readonly primary = input.required<string>();
  readonly secondary = input('');
  readonly alt = input('');
  readonly loading = input<'lazy' | 'eager'>('lazy');
  private readonly unavailable = signal<string[]>([]);
  protected readonly source = computed(() => {
    const unavailable = this.unavailable();
    return [this.primary(), this.secondary()].find((url) => url && !unavailable.includes(url));
  });
  protected readonly demoPhoto = computed(() => this.source()?.match(demoPhotoUrl)?.[1]);

  protected markUnavailable(url: string): void {
    this.unavailable.update((urls) => [...urls, url]);
  }
}
